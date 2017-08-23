package com.utosft.block.lib.client;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.ConfigFileApplicationContextInitializer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import com.alibaba.fastjson.JSON;
import com.utsoft.blockchain.api.exception.CryptionException;
import com.utsoft.blockchain.api.pojo.BaseResponseModel;
import com.utsoft.blockchain.api.pojo.TkcQueryDetailRspVo;
import com.utsoft.blockchain.api.pojo.TkcSubmitRspVo;
import com.utsoft.blockchain.api.pojo.TkcTransactionBlockInfoVo;
import com.utsoft.blockchain.api.pojo.TransactionVarModel;
import com.utsoft.blockchain.api.pojo.UserInfoRequstModel;
import com.utsoft.blockchain.api.pojo.UserInfoRspModel;
import com.utsoft.blockchain.api.proivder.ITkcAccountStoreExportService;
import com.utsoft.blockchain.api.proivder.ITkcTransactionExportService;
import com.utsoft.blockchain.api.security.FamilySecCrypto;
import com.utsoft.blockchain.api.util.SdkUtil;
import com.utsoft.blockchain.api.util.SignaturePlayload;
import com.utsoft.blockchain.lib.ServiceClientApplication;
@RunWith(SpringJUnit4ClassRunner.class)     
@ContextConfiguration(initializers={ConfigFileApplicationContextInitializer.class})
@SpringBootTest(classes=ServiceClientApplication.class)
public class ApiTest {

	
	@Value("${apply.category}")
	private String applyCategory;
	 
	@Autowired
	ITkcTransactionExportService tkcTransactionExportService;
	
	@Autowired
	private ITkcAccountStoreExportService tkcAccountStoreExportService;
	
	 private String privateKey;
	 
	 @Value("${user.username}")
	 private String username;
	 
	 @Value("${user.password}")
	 private String password ;
	 
	 @Value("${user.toUser}")
	 private String toUser;

	 @Value("${user.token}")
	 private String token ; 
	 
	 private static String txId ="37f2a0dfeac0ab06e5bdf7db58e2821e6160fe56c486a456f678c4b63486c9ef";
	@Before
	public void setup() {
		
		BaseResponseModel<UserInfoRspModel> userPrivateKeyAccess = tkcAccountStoreExportService.getIndividualAccout(username,token);
	    if (userPrivateKeyAccess.isSuccess()) {
		    privateKey = userPrivateKeyAccess.getData().getPrivateKey();
	    } else   {
	    	 String  created = SdkUtil.generateId();
		    
	    	 UserInfoRequstModel requestModel = new UserInfoRequstModel();
			 requestModel.setUserName(username);
			 requestModel.setPassword(password);
			 requestModel.setCreated(created);
			
			BaseResponseModel<UserInfoRspModel> baseResponse = tkcAccountStoreExportService.register(requestModel);
			if (baseResponse.getData()!=null) {
				String publicKey = baseResponse.getData().getPrivateKey();
				token =baseResponse.getData().getToken();
				System.out.println(password+":"+publicKey);
			}
			userPrivateKeyAccess = tkcAccountStoreExportService.getIndividualAccout(username,token);
			if (!userPrivateKeyAccess.isSuccess()) {
				fail("user not exists");
		    }
			 privateKey = userPrivateKeyAccess.getData().getPrivateKey();
	   }
	}
	 
	@Test
	public void testQueryAccoutInfo() {
		
		FamilySecCrypto familyCrypto = FamilySecCrypto.Factory.getCryptoSuite();
		SignaturePlayload signaturePlayload = new SignaturePlayload(familyCrypto);
	
		String created = SdkUtil.generateId();
		String from = username;
		
		/**
		 * 注意顺序
		 */
		signaturePlayload.addPlayload(applyCategory);
		signaturePlayload.addPlayload(from);
		signaturePlayload.addPlayload(created);
		
		 String sign;
		 try {
			sign = signaturePlayload.doSignature(privateKey);
		  } catch (CryptionException e) {
			e.printStackTrace();
			fail("not sign success ");
			return ;
		}
		
		BaseResponseModel<TkcQueryDetailRspVo> baseResponse = tkcTransactionExportService.getAccountDetail(applyCategory, from, created, sign);
		System.out.println(JSON.toJSON(baseResponse.getData()));
		assertEquals(baseResponse.getCode(),"200");
	}
	
	
	@Test
	public void testRegister() {
		String  created = SdkUtil.generateId();
		UserInfoRequstModel requestModel = new UserInfoRequstModel();
		requestModel.setUserName(username);
		requestModel.setPassword(password);
		requestModel.setCreated(created);
		
		BaseResponseModel<UserInfoRspModel> baseResponse = tkcAccountStoreExportService.register(requestModel);
		if (baseResponse.getData()!=null) {
			String  privateKey = baseResponse.getData().getPrivateKey();
			 token = baseResponse.getData().getToken();
			System.out.println(token+":"+privateKey);
		}
	}
	
	@Test
	public void testGetPublicKey() {
		
		BaseResponseModel<UserInfoRspModel> baseResponse = tkcAccountStoreExportService.getIndividualAccout(username,token);
		if (baseResponse.getData()!=null){
			String publicKey = baseResponse.getData().getPrivateKey();
			String token1 = baseResponse.getData().getToken();
			System.out.println(token1+":"+publicKey);
		}
		assertEquals(baseResponse.getCode(),"200");
	}	
	
	
	/**
	 * 支付
	 */
	@Test
	public void testMoveAToB() {
		
		String to = toUser;
		String submitJson ="10";
		String created = SdkUtil.generateId();
		
		TransactionVarModel model = new TransactionVarModel(applyCategory,"move");
		model.setCreated(created);
		model.setFrom(username);
		model.setTo(to);
		model.setSubmitJson(submitJson);
		
		FamilySecCrypto familyCrypto = FamilySecCrypto.Factory.getCryptoSuite();
		SignaturePlayload signaturePlayload = new SignaturePlayload(familyCrypto);
		String from = username;
		/**
		 * md5(applyCategory=1&from=2&to=3&cmd=4&submitJson=5&created=xxx)
		 * 注意顺序
		 */
		signaturePlayload.addPlayload(applyCategory);
		signaturePlayload.addPlayload(from);
		signaturePlayload.addPlayload(to);
		signaturePlayload.addPlayload("move");
		signaturePlayload.addPlayload(submitJson);
		signaturePlayload.addPlayload(created);
		String sign;
		 try {
			sign = signaturePlayload.doSignature(privateKey);
		  } catch (CryptionException e) {
			e.printStackTrace();
			fail("not sign success ");
			return ;
		}
		 BaseResponseModel<TkcSubmitRspVo> baseResponse = tkcTransactionExportService.tranfer(model,sign);
		 assertEquals(baseResponse.getCode(),200);
		 if (baseResponse.isSuccess()) {
			 txId = baseResponse.getData().getTxId();
			 System.out.println(JSON.toJSON(baseResponse.getData()));
		 } 
	}
	
	/**
	 * 
	 */
	@Test
	public void queryTxBlockInfo() {
	
		FamilySecCrypto familyCrypto = FamilySecCrypto.Factory.getCryptoSuite();
		SignaturePlayload signaturePlayload = new SignaturePlayload(familyCrypto);
	
		String created = SdkUtil.generateId();
		String from = username;
		
		/**
		 * 注意顺序
		 */
		signaturePlayload.addPlayload(applyCategory);
		signaturePlayload.addPlayload(from);
		signaturePlayload.addPlayload(txId);
		signaturePlayload.addPlayload(created);
		
		 String sign;
		 try {
			sign = signaturePlayload.doSignature(privateKey);
		  } catch (CryptionException e) {
			e.printStackTrace();
			fail("not sign success ");
			return ;
		}
	   BaseResponseModel<TkcTransactionBlockInfoVo> baseResponse = tkcTransactionExportService.listStockChanges(applyCategory,from, txId, created, sign);
	   assertEquals(baseResponse.getCode(),200);
	   if (baseResponse.isSuccess()) {
		   System.out.println(JSON.toJSON(baseResponse.getData()));
	   }
	}	
}