package net.xdob.vexra.security;

public class RsaHelper {
  public static final String KEY = "vexra";
	private String pubKey;
	private String priKey;

	public RsaHelper(String pubKey){
		this(pubKey, null);
	}

	public RsaHelper(String pubKey, String priKey) {
		this.pubKey = pubKey;
		this.priKey = priKey;
	}

	public String encrypt(String text){
    RSAUtil rsaUtil = RSAUtil.create();
    return rsaUtil.encryptByPublicKey(pubKey, text);
  }

  public String decrypt(String text){
    RSAUtil rsaUtil = RSAUtil.create();
		if(priKey==null){
			throw new NullPointerException("private key is null");
		}
    return rsaUtil.decryptByPrivateKey(priKey, text);
  }

}
