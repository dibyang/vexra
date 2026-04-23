package net.xdob.vexra.security;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Java AES-256-CBC 加密解密工具类（生产级，无第三方依赖，JDK8+直接用）
 * 核心：对称加密、密钥32字节、IV偏移量16字节、UTF-8编码、Base64转码密文
 */
public class AES256Util {
  // 核心算法固定写法：AES算法 + CBC模式 + PKCS5填充
  private static final String AES_ALGORITHM = "AES/CBC/PKCS5Padding";
  // AES算法名称
  private static final String AES_NAME = "AES";

  /**
   * 加密方法
   * @param content  待加密的明文（比如：用户手机号、密码、订单号、敏感业务数据）
   * @param key      32字节的AES密钥（必须32位，工具类提供了生成方法）
   * @param iv       16字节的偏移量（必须16位，工具类提供了生成方法）
   * @return 加密后的密文（Base64格式，方便存储和传输，无乱码）
   */
  public static String encrypt(String content, String key, String iv) {
    try {
      // 1. 构建密钥对象：密钥必须转成UTF-8字节数组，固定32位
      SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), AES_NAME);
      // 2. 构建偏移量对象：IV必须转成UTF-8字节数组，固定16位
      IvParameterSpec ivParameterSpec = new IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8));
      // 3. 创建加密器，初始化加密模式
      Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
      cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivParameterSpec);
      // 4. 加密：明文转UTF-8字节数组 → 加密字节数组 → Base64编码成字符串
      byte[] encryptBytes = cipher.doFinal(content.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(encryptBytes);
    } catch (Exception e) {
      throw new RuntimeException("AES256加密失败", e);
    }
  }

  /**
   * 解密方法
   * @param encryptStr 加密后的Base64格式密文
   * @param key        加密时用的32字节密钥（对称加密，密钥必须一致）
   * @param iv         加密时用的16字节偏移量（必须一致）
   * @return 解密后的明文
   */
  public static String decrypt(String encryptStr, String key, String iv) {
    try {
      // 1. 构建密钥对象
      SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), AES_NAME);
      // 2. 构建偏移量对象
      IvParameterSpec ivParameterSpec = new IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8));
      // 3. 创建解密器，初始化解密模式
      Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
      cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec);
      // 4. 解密：Base64解码成加密字节数组 → 解密字节数组 → UTF-8转成明文
      byte[] decryptBytes = cipher.doFinal(Base64.getDecoder().decode(encryptStr));
      return new String(decryptBytes, StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new RuntimeException("AES256解密失败", e);
    }
  }

  /**
   * 生成符合规范的 AES-256 密钥
   * @return 32字节的随机密钥字符串（直接可用，无需二次处理）
   */
  public static String generateAes256Key() {
    return generateRandomStr(32);
  }

  /**
   * 生成符合规范的 CBC模式 偏移量IV
   * @return 16字节的随机偏移量字符串（直接可用，无需二次处理）
   */
  public static String generateAesIv() {
    return generateRandomStr(16);
  }

  /**
   * 生成指定长度的随机字符串（用于生成密钥/IV，安全随机）
   */
  private static String generateRandomStr(int length) {
    String base = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#$%^&*()_+-=";
    SecureRandom random = new SecureRandom();
    StringBuilder sb = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      sb.append(base.charAt(random.nextInt(base.length())));
    }
    return sb.toString();
  }

  // ========== 测试：直接运行main方法即可验证加解密 ==========
  public static void main(String[] args) {
    // 1. 待加密的敏感明文
    String content = "Java AES-256加密最可靠！我的手机号是13888888888，密码是123456";

    // 2. 生成32位密钥 + 16位偏移量（生产环境：密钥和IV要妥善保管，不要硬编码！）
    String aesKey = generateAes256Key();
    String aesIv = generateAesIv();

    System.out.println("生成的32位AES密钥：" + aesKey);
    System.out.println("生成的16位IV偏移量：" + aesIv);
    System.out.println("待加密明文：" + content);

    // 3. 加密
    String encryptStr = encrypt(content, aesKey, aesIv);
    System.out.println("加密后的密文：" + encryptStr);

    // 4. 解密
    String decryptStr = decrypt(encryptStr, aesKey, aesIv);
    System.out.println("解密后的明文：" + decryptStr);

    // 验证：明文和解密后的内容是否一致
    System.out.println("加解密是否一致：" + content.equals(decryptStr));
  }
}

