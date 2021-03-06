package com.utsoft.blockchain.core.fabric.channel;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import org.apache.commons.codec.binary.Hex;
import org.hyperledger.fabric.protos.peer.FabricProposal;
import org.hyperledger.fabric.protos.peer.FabricProposalResponse;
import org.hyperledger.fabric.protos.peer.Query.ChaincodeInfo;
import org.hyperledger.fabric.sdk.BlockEvent;
import org.hyperledger.fabric.sdk.BlockEvent.TransactionEvent;
import org.hyperledger.fabric.sdk.BlockListener;
import org.hyperledger.fabric.sdk.ChaincodeEndorsementPolicy;
import org.hyperledger.fabric.sdk.ChaincodeID;
import org.hyperledger.fabric.sdk.ChaincodeResponse.Status;
import org.hyperledger.fabric.sdk.Channel;
import org.hyperledger.fabric.sdk.ChannelConfiguration;
import org.hyperledger.fabric.sdk.EventHub;
import org.hyperledger.fabric.sdk.HFClient;
import org.hyperledger.fabric.sdk.InstallProposalRequest;
import org.hyperledger.fabric.sdk.InstantiateProposalRequest;
import org.hyperledger.fabric.sdk.Orderer;
import org.hyperledger.fabric.sdk.Peer;
import org.hyperledger.fabric.sdk.ProposalResponse;
import org.hyperledger.fabric.sdk.QueryByChaincodeRequest;
import org.hyperledger.fabric.sdk.SDKUtils;
import org.hyperledger.fabric.sdk.TransactionProposalRequest;
import org.hyperledger.fabric.sdk.exception.ChaincodeEndorsementPolicyParseException;
import org.hyperledger.fabric.sdk.exception.InvalidArgumentException;
import org.hyperledger.fabric.sdk.exception.ProposalException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.ByteString;
import com.utsoft.blockchain.api.exception.ServiceProcessException;
import com.utsoft.blockchain.api.pojo.ReqtOrderDto;
import com.utsoft.blockchain.api.pojo.ReqtQueryOrderDto;
import com.utsoft.blockchain.api.pojo.RspQueryResultDto;
import com.utsoft.blockchain.api.pojo.SubmitRspResultDto;
import com.utsoft.blockchain.core.fabric.model.FabricAuthorizedOrg;
import com.utsoft.blockchain.core.util.CommonUtil;
import com.utsoft.blockchain.core.util.FormatUtil;
import com.utsoft.blockchain.core.util.IGlobals;
import com.utsoft.blockchain.core.util.LocalConstants;

/**
 * 区块链基本操作逻辑封装
 * @author hunterfox
 * @date: 2017年7月28日
 * @version 1.0.0
 */
public class ChannelClientProxy {

	protected final Logger logger = LoggerFactory.getLogger(this.getClass());
	
	
	private BlockListener blockListener = new BlockListener(){

		@Override
		public void received(BlockEvent blockEvent) {
			 
			long blockNumber = blockEvent.getBlockNumber();
			String dataHash = Hex.encodeHexString(blockEvent.getDataHash());
			String previousHash =   Hex.encodeHexString(blockEvent.getPreviousHash());
			Object[] args = {blockNumber,dataHash,previousHash};
			
			logger.info("blockNumber:{} dataHash: {}  previousHash: {}",args);
		}
	};
	
	public Channel connectChannel(HFClient client, String name, FabricAuthorizedOrg orgconfig, ChaincodeID chaincodeID)
			throws Exception {

		client.setUserContext(orgconfig.getPeerAdmin());

		Channel newChannel = client.newChannel(name);
		newChannel.registerBlockListener(blockListener);
		for (String orderName : orgconfig.getOrdererNames()) {
			Orderer orders = client.newOrderer(orderName, orgconfig.getOrdererLocation(orderName),
					CommonUtil.getOrdererProperties(orderName));
			newChannel.addOrderer(orders);
		}

		for (String peerName : orgconfig.getPeerNames()) {
			String peerLocation = orgconfig.getPeerLocation(peerName);
			Peer peer = client.newPeer(peerName, peerLocation, CommonUtil.getPeerProperties(peerName));

			// Query the actual peer for which channels it belongs to and check
			// it belongs to this channel
			Set<String> channels = client.queryChannels(peer);
			if (!channels.contains(name)) {
				throw new AssertionError(format("Peer %s does not appear to belong to channel %s", peerName, name));
			}
			newChannel.addPeer(peer);
			orgconfig.addPeer(peer);
		}
		for (String eventHubName : orgconfig.getEventHubNames()) {
			EventHub eventHub = client.newEventHub(eventHubName, orgconfig.getEventHubLocation(eventHubName),
					CommonUtil.getEventHubProperties(eventHubName));
			newChannel.addEventHub(eventHub);
		}
		int waitTime = IGlobals.getIntProperty(LocalConstants.PROPOSALWAITTIME, 120000);
		newChannel.setTransactionWaitTime(waitTime);
		newChannel.initialize();

		// Before return lets see if we have the chaincode on the peers that we
		// expect from End2endIT
		// And if they were instantiated too.

		for (Peer peer : newChannel.getPeers()) {

			if (!checkInstalledChaincode(client, peer, chaincodeID.getName(), chaincodeID.getPath(),
					chaincodeID.getVersion())) {
				throw new AssertionError(format("Peer %s is missing chaincode name: %s, path:%s, version: %s",
						peer.getName(), chaincodeID.getName(), chaincodeID.getPath(), chaincodeID.getVersion()));
			}

			if (!checkInstantiatedChaincode(newChannel, peer, chaincodeID.getName(), chaincodeID.getPath(),
					chaincodeID.getVersion())) {

				throw new AssertionError(format(
						"Peer %s is missing instantiated chaincode name: %s, path:%s, version: %s", peer.getName(),
						chaincodeID.getName(), chaincodeID.getPath(), chaincodeID.getVersion()));
			}
		}
		return newChannel;
	}

	/**
	 * peer 检查链码是否安装
	 * 
	 * @param client
	 * @param peer
	 * @param ccName
	 * @param ccPath
	 * @param ccVersion
	 * @return
	 * @throws InvalidArgumentException
	 * @throws ProposalException
	 */
	private boolean checkInstalledChaincode(HFClient client, Peer peer, String ccName, String ccPath, String ccVersion)
			throws InvalidArgumentException, ProposalException {

		if (logger.isInfoEnabled())
			logger.info(FormatUtil.formater("Checking installed chaincode: %s, at version: %s, on peer: %s", ccName,
					ccVersion, peer.getName()));

		List<ChaincodeInfo> ccinfoList = client.queryInstalledChaincodes(peer);
		boolean found = false;
		for (ChaincodeInfo ccifo : ccinfoList) {
			found = ccName.equals(ccifo.getName()) && ccPath.equals(ccifo.getPath())
					&& ccVersion.equals(ccifo.getVersion());
			logger.info("ccifo:"+ccifo.toString());
			if (found) {
				break;
			}
		}
		return found;
	}

	/**
	 * peer 检查链码是否安装
	 * 
	 * @param channel
	 * @param peer
	 * @param ccName
	 * @param ccPath
	 * @param ccVersion
	 * @return
	 * @throws InvalidArgumentException
	 * @throws ProposalException
	 */
	private boolean checkInstantiatedChaincode(Channel channel, Peer peer, String ccName, String ccPath,
			String ccVersion) throws InvalidArgumentException, ProposalException {

		if (logger.isInfoEnabled())
			logger.info(FormatUtil.formater("Checking instantiated chaincode: %s, at version: %s, on peer: %s", ccName,
					ccVersion, peer.getName()));

		List<ChaincodeInfo> ccinfoList = channel.queryInstantiatedChaincodes(peer);
		boolean found = false;
		for (ChaincodeInfo ccifo : ccinfoList) {
			found = ccName.equals(ccifo.getName()) && ccPath.equals(ccifo.getPath())
					&& ccVersion.equals(ccifo.getVersion());
			if (found) {
				break;
			}
		}
		return found;
	}

	/**
	 * 主要是事物请求，交易操作
	 * @param chaincodeID
	 * @param order
	 * @throws InvalidArgumentException
	 */
	public CompletableFuture<TransactionEvent> submitRequest(HFClient client, Channel newChannel,
			ChaincodeID chaincodeID, ReqtOrderDto order, SubmitRspResultDto result) throws Exception {

		Collection<ProposalResponse> successful = new LinkedList<>();
		Collection<ProposalResponse> failed = new LinkedList<>();

		String[] submit;
		if (order.getFromAccount()!=null) {
			submit = new String[] {"move", order.getCmd(), order.getToAccount(),order.getFromAccount(), order.getJson() };
		} else {
			submit = new String[] { "move",order.getCmd(), order.getToAccount(),"", order.getJson() };
		}
		int proposalWaitTime = IGlobals.getIntProperty(LocalConstants.PROPOSALWAITTIME, 12000);
		TransactionProposalRequest transactionProposalRequest = client.newTransactionProposalRequest();
		transactionProposalRequest.setChaincodeID(chaincodeID);
		transactionProposalRequest.setFcn("invoke");
		transactionProposalRequest.setProposalWaitTime(proposalWaitTime);
		transactionProposalRequest.setArgs(submit);
		/*
		 * Map<String, byte[]> tm2 = new HashMap<>();
		 * tm2.put("HyperLedgerFabric",
		 * "TransactionProposalRequest:JavaSDK".getBytes(UTF_8));
		 * tm2.put("method", "TransactionProposalRequest".getBytes(UTF_8));
		 * tm2.put("result", ":)".getBytes(UTF_8)); /// This should be returned
		 * see chaincode. transactionProposalRequest.setTransientMap(tm2);
		 */

		Collection<ProposalResponse> transactionPropResp = newChannel
				.sendTransactionProposal(transactionProposalRequest, newChannel.getPeers());
		for (ProposalResponse response : transactionPropResp) {
			if (response.getStatus() == ProposalResponse.Status.SUCCESS) {

				if (logger.isInfoEnabled()) {
					String format = FormatUtil.formater(
							"Successful transaction proposal response Txid: %s from peer %s",
							response.getTransactionID(), response.getPeer().getName());
					logger.info(format);
				}

				successful.add(response);
			} else {
				failed.add(response);
			}
		}

		// Check that all the proposals are consistent with each other. We
		// should have only one set
		// where all the proposals above are consistent.
		Collection<Set<ProposalResponse>> proposalConsistencySets = SDKUtils
				.getProposalConsistencySets(transactionPropResp);
		if (proposalConsistencySets.size() != 1) {
			String msg = FormatUtil.formater("Expected only one set of consistent proposal responses but got %d",
					proposalConsistencySets.size());
			logger.warn(msg);
		}

		if (failed.size() > 0) {
			ProposalResponse firstTransactionProposalResponse = failed.iterator().next();
			logger.info("Not enough endorsers for invoke(move a,b,100):" + failed.size() + " endorser error: "
					+ firstTransactionProposalResponse.getMessage() + ". Was verified: "
					+ firstTransactionProposalResponse.isVerified());
		}

         FabricProposal.Proposal proposal = null;
         for (ProposalResponse sdkProposalResponse : successful) {
           
             if (proposal == null) {
                 proposal = sdkProposalResponse.getProposal();
                 result.setTxId(sdkProposalResponse.getTransactionID());
             }
         }
         
		/*
		 * ProposalResponse resp = transactionPropResp.iterator().next(); byte[]
		 * x = resp.getChaincodeActionResponsePayload(); // This is the data
		 * returned by the chaincode. String resultAsString = null; if (x !=
		 * null) { resultAsString = new String(x, "UTF-8"); } TxReadWriteSetInfo
		 * readWriteSetInfo = resp.getChaincodeActionResponseReadWriteSetInfo();
		 * if (resp.getChaincodeActionResponseStatus()==200) {
		 * 
		 * } int newSetCount = readWriteSetInfo.getNsRwsetCount();
		 */
		// int invokeWaitTime =
		// IGlobals.getIntProperty(Constants.INVOKEWAITTIME, 100000);
		return newChannel.sendTransaction(successful);
	}

	/**
	 * 区块链交易查询
	 * 
	 * @param client
	 * @param channel
	 * @param expect
	 * @param chaincodeID
	 * @param reqtQueryOrderDto
	 * @return
	 */

	public RspQueryResultDto queryChaincode(HFClient client, Channel channel, ChaincodeID chaincodeID,
			ReqtQueryOrderDto reqtQueryOrderDto) {

		RspQueryResultDto rspQueryResultDto = null;
		long consumerTime = System.currentTimeMillis();

		List<String> objects = new ArrayList<String>();
		objects.add("query");
		objects.add(reqtQueryOrderDto.getCmd().toLowerCase());
		
		if (reqtQueryOrderDto.getToAccount() != null) {
			objects.add(reqtQueryOrderDto.getToAccount());
		}
		
	    if(IGlobals.getBooleanProperty(LocalConstants.NOT_DEBUG_MODE,true)){
				objects.add(""); 
		}
			
		if (reqtQueryOrderDto.getJson() != null) {
			objects.add(reqtQueryOrderDto.getJson());
		}

		String[] queryConent = new String[objects.size()];
		objects.toArray(queryConent);

		QueryByChaincodeRequest queryByChaincodeRequest = client.newQueryProposalRequest();
		queryByChaincodeRequest.setArgs(queryConent);
		queryByChaincodeRequest.setFcn("invoke");
		queryByChaincodeRequest.setChaincodeID(chaincodeID);

		Collection<ProposalResponse> queryProposals;
		try {
			queryProposals = channel.queryByChaincode(queryByChaincodeRequest);
		} catch (Exception e) {
			logger.error("Failed during chaincode query with error {} error:{}", objects);
			throw new CompletionException(e);
		}

		List<RspQueryResultDto> results = new ArrayList<>();
		for (ProposalResponse proposalResponse : queryProposals) {

			if (proposalResponse.getStatus() == Status.SUCCESS && proposalResponse.isVerified()) {
				String payload = proposalResponse.getProposalResponse().getResponse().getPayload().toStringUtf8();
				rspQueryResultDto = new RspQueryResultDto();
				rspQueryResultDto.setPayload(payload);
				rspQueryResultDto.setTimestamp(System.currentTimeMillis() - consumerTime);
				results.add(rspQueryResultDto);

			} else {
				logger.error("Failed query proposal from peer " + proposalResponse.getPeer().getName() + " status: "
						+ proposalResponse.getStatus() + ". Messages: " + proposalResponse.getMessage()
						+ ". Was verified : " + proposalResponse.isVerified());
			}
		}
		return CommonUtil.isCollectNotEmpty(results) ? results.get(0) : null;
	}
	
	/**
	 * 初始化channel
	 * @param client
	 * @param orgconfig
	 * @param txPath
	 * @return
	 */
    public Channel initializeNewChannel(HFClient client,FabricAuthorizedOrg orgconfig,File txPath) {
			
		 try {
			  client.setUserContext(orgconfig.getPeerAdmin());
			  Collection<Orderer> orderers = new LinkedList<>();
			  for (String orderName : orgconfig.getOrdererNames()) {
				  Orderer orders = client.newOrderer(orderName, orgconfig.getOrdererLocation(orderName),
							CommonUtil.getOrdererProperties(orderName));
					orderers.add(orders);
				}
			
			   Orderer singleOrder = orderers.iterator().next();
		       orderers.remove(singleOrder);
			   ChannelConfiguration channelConfiguration = new ChannelConfiguration(txPath);
			   Channel  newChannel = client.newChannel(orgconfig.getChannelName(),singleOrder,channelConfiguration,client.getChannelConfigurationSignature(channelConfiguration, orgconfig.getPeerAdmin())); 

		        for (String peerName : orgconfig.getPeerNames()) {
		            String peerLocation = orgconfig.getPeerLocation(peerName);

		            Properties peerProperties = CommonUtil.getPeerProperties(peerName);
		            if (peerProperties == null) {
		                peerProperties = new Properties();
		            }
	
		            peerProperties.put("grpc.NettyChannelBuilderOption.maxInboundMessageSize", 9000000);
		            Peer peer = client.newPeer(peerName, peerLocation, peerProperties);
		            newChannel.joinPeer(peer);
		            logger.info(format("Peer %s joined channel %s", peerName, orgconfig));
		            orgconfig.addPeer(peer);
		        }
		       
		        //add remaining orderers if any.
		        for (Orderer orderer : orderers) { 
		            newChannel.addOrderer(orderer);
		        }
		        for (String eventHubName : orgconfig.getEventHubNames()) {
		            final Properties eventHubProperties = CommonUtil.getEventHubProperties(eventHubName);
		            eventHubProperties.put("grpc.NettyChannelBuilderOption.keepAliveTime", new Object[] {5L, TimeUnit.MINUTES});
		            eventHubProperties.put("grpc.NettyChannelBuilderOption.keepAliveTimeout", new Object[] {8L, TimeUnit.SECONDS});
		            EventHub eventHub = client.newEventHub(eventHubName, orgconfig.getEventHubLocation(eventHubName),
		                    eventHubProperties);
		            newChannel.addEventHub(eventHub);
		        }
		       return newChannel.initialize();
		  } catch (Exception e)  {
			  throw new AssertionError(format("install all peer fail:",orgconfig,txPath));
		  }
	}
	 
	 
	/**
	 * install  chaincode in channel
	 * @param client
	 * @param channel
	 * @param orgconfig
	 * @param chaincodeID
	 * @param installPath
	 */
	public void install(HFClient client, Channel channel, FabricAuthorizedOrg orgconfig,ChaincodeID chaincodeID,File installPath) {
		     
		      if (channel==null) {
		       try {
		    		  channel = initEmptyChannel(client,orgconfig.getChannelName(),orgconfig,chaincodeID);
				 } catch (Exception e) {
					 throw new ServiceProcessException("install channel "+e);
				 } 
		      }
		    try {
			 client.setUserContext(orgconfig.getPeerAdmin());
			 InstallProposalRequest installProposalRequest = client.newInstallProposalRequest();
             installProposalRequest.setChaincodeID(chaincodeID);
             installProposalRequest.setChaincodeVersion(chaincodeID.getVersion());
             installProposalRequest.setChaincodeSourceLocation(installPath);
            
             Collection<ProposalResponse> responses;
             
             int numInstallProposal = 0;
         
             Collection<ProposalResponse> successful = new LinkedList<>();
             Collection<ProposalResponse> failed = new LinkedList<>();
             
             Set<Peer> peersFromOrg = orgconfig.getPeers();
             numInstallProposal = numInstallProposal + peersFromOrg.size();
             responses = client.sendInstallProposal(installProposalRequest, peersFromOrg);

             for (ProposalResponse response : responses) {
                 if (response.getStatus() == ProposalResponse.Status.SUCCESS) {
                	 logger.info(FormatUtil.formater("Successful install proposal response Txid: %s from peer %s", response.getTransactionID(), response.getPeer().getName()));
                     successful.add(response);
                 } else {
                     failed.add(response);
                 }
             }
             SDKUtils.getProposalConsistencySets(responses);
             logger.info(FormatUtil.formater("Received %d install proposal responses. Successful+verified: %d . Failed: %d", numInstallProposal, successful.size(), failed.size()));

             if (failed.size() > 0) {
                 ProposalResponse first = failed.iterator().next();
                 logger.error("Not enough endorsers for install :" + successful.size() + ".  " + first.getMessage());
             }
             
		 } catch (InvalidArgumentException | ProposalException e) {
			 throw new AssertionError(format("install all peer fail:",orgconfig,chaincodeID,installPath));
		}
	}
	
	/**
	 * instantiate chaincode in channel
	 * @param client
	 * @param channel
	 * @param chaincodeendorsementpolicy
	 * @param orgconfig
	 * @param chaincodeID
	 * @param objects
	 * @return 
	 */
	public CompletableFuture<TransactionEvent> instantiate(HFClient client, Channel channel,File chaincodeendorsementpolicy ,FabricAuthorizedOrg orgconfig,ChaincodeID chaincodeID,List<String> objects) {
		
		    if (channel==null) {
		       try {
		    		  channel = initEmptyChannel(client,orgconfig.getChannelName(),orgconfig,chaincodeID);
				 } catch (Exception e) {
					 throw new ServiceProcessException("install channel "+e);
				 } 
		   } 
		 
		  String[] inits = new String[objects.size()];
		  objects.toArray(inits);
	 	
		  Collection<ProposalResponse> successful = new LinkedList<>();
          Collection<ProposalResponse> failed = new LinkedList<>();
          Collection<ProposalResponse> responses;
          
		  int proposalWaitTime = IGlobals.getIntProperty(LocalConstants.PROPOSALWAITTIME, 120000);
	      InstantiateProposalRequest instantiateProposalRequest = client.newInstantiationProposalRequest();
          instantiateProposalRequest.setProposalWaitTime(proposalWaitTime);
          instantiateProposalRequest.setChaincodeID(chaincodeID);
          instantiateProposalRequest.setFcn("init");
          instantiateProposalRequest.setArgs(inits);
          Map<String, byte[]> tm = new HashMap<>();
          tm.put("HyperLedgerFabric", "InstantiateProposalRequest:JavaSDK".getBytes(UTF_8));
          tm.put("method", "InstantiateProposalRequest".getBytes(UTF_8));
          tm.put("NetworkID", "tkcblockchaintest_byfn".getBytes(UTF_8));
          try {
			instantiateProposalRequest.setTransientMap(tm);
		
          
          ChaincodeEndorsementPolicy chaincodeEndorsementPolicy = new ChaincodeEndorsementPolicy();
          chaincodeEndorsementPolicy.fromYamlFile(chaincodeendorsementpolicy);
          instantiateProposalRequest.setChaincodeEndorsementPolicy(chaincodeEndorsementPolicy);

          logger.info("Sending instantiateProposalRequest to all peers with arguments: a and b set to  and %s respectively");
          successful.clear();
          failed.clear();
          
          responses = channel.sendInstantiationProposal(instantiateProposalRequest,channel.getPeers());
          for (ProposalResponse response : responses) {
              if (response.isVerified() && response.getStatus() == ProposalResponse.Status.SUCCESS) {
                  successful.add(response);
                  logger.info(FormatUtil.formater("Succesful instantiate proposal response Txid: %s from peer %s", response.getTransactionID(), response.getPeer().getName()));
              } else {
                  failed.add(response);
              }
          }
           logger.info(FormatUtil.formater("Received %d instantiate proposal responses. Successful+verified: %d . Failed: %d", responses.size(), successful.size(), failed.size()));
            if (failed.size() > 0) {
              ProposalResponse first = failed.iterator().next();
              logger.error("Not enough endorsers for instantiate :" + successful.size() + "endorser failed with " + first.getMessage() + ". Was verified:" + first.isVerified());
            } 
         } catch (InvalidArgumentException | ChaincodeEndorsementPolicyParseException | IOException | ProposalException e) {
        	  Object[] errorObjects  = {chaincodeendorsementpolicy,orgconfig,chaincodeID,objects,e};
        	  logger.error("instantiate fail {}-{}-{} {}-{} :error{}",errorObjects);
  		 }
          Collection<Orderer> orderers = channel.getOrderers();
          return channel.sendTransaction(successful, orderers);
	}
	
	
	private Channel initEmptyChannel(HFClient client, String name, FabricAuthorizedOrg orgconfig, ChaincodeID chaincodeID)
			throws Exception {

		client.setUserContext(orgconfig.getPeerAdmin());

		Channel newChannel = client.newChannel(name);
		for (String orderName : orgconfig.getOrdererNames()) {
			Orderer orders = client.newOrderer(orderName, orgconfig.getOrdererLocation(orderName),
					CommonUtil.getOrdererProperties(orderName));
			newChannel.addOrderer(orders);
		}

		for (String peerName : orgconfig.getPeerNames()) {
			String peerLocation = orgconfig.getPeerLocation(peerName);
			Peer peer = client.newPeer(peerName, peerLocation, CommonUtil.getPeerProperties(peerName));

			// Query the actual peer for which channels it belongs to and check
			// it belongs to this channel
			Set<String> channels = client.queryChannels(peer);
			if (!channels.contains(name)) {
				throw new AssertionError(format("Peer %s does not appear to belong to channel %s", peerName, name));
			}
			newChannel.addPeer(peer);
			orgconfig.addPeer(peer);
		}
		for (String eventHubName : orgconfig.getEventHubNames()) {
			EventHub eventHub = client.newEventHub(eventHubName, orgconfig.getEventHubLocation(eventHubName),
					CommonUtil.getEventHubProperties(eventHubName));
			newChannel.addEventHub(eventHub);
		}
		int waitTime = IGlobals.getIntProperty(LocalConstants.PROPOSALWAITTIME, 120000);
		newChannel.setTransactionWaitTime(waitTime);
		newChannel.initialize();
		return newChannel;
	}
}
