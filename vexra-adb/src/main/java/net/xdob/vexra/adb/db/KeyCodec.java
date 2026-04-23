package net.xdob.vexra.adb.db;

import java.util.Arrays;

public class KeyCodec {


  /**
   * 符号位翻转
   */
  static long flipSign(long v) {
    return v ^ Long.MIN_VALUE;
  }
  /**
   * 符号位翻转
   */
  static int flipSign(int v) {
    return v ^ Integer.MIN_VALUE;
  }


  public static byte[] prefixEnd(byte[] prefix) {
    if (prefix == null) return null;

    byte[] data = Arrays.copyOf(prefix, prefix.length);

    // 从末尾往前找第一个不是 0xff 的字节
    for (int i = data.length - 1; i >= 0; i--) {
      int v = data[i] & 0xFF;
      if (v != 0xFF) {
        data[i] = (byte) (v + 1);
        // 保留整个数组长度，不截断
        return data;
      }
    }

    // 全是 0xff，没有上界，返回 null
    return null;
  }


  public static int compare(byte[] a, byte[] b) {
    int len = Math.min(a.length, b.length);
    for (int i = 0; i < len; i++) {
      int diff = (a[i] & 0xFF) - (b[i] & 0xFF);
      if (diff != 0) {
        return diff;
      }
    }
    return a.length - b.length;
  }

  public static boolean equals(byte[] a, byte[] b) {
    if (a == b) return true;
    if (a == null || b == null) return false;
    if (a.length != b.length) return false;
    for (int i = 0; i < a.length; i++) {
      if (a[i] != b[i]) return false;
    }
    return true;
  }

  public static boolean startsWith(byte[] a, byte[] prefix) {
    if (a.length < prefix.length) return false;
    for (int i = 0; i < prefix.length; i++) {
      if (a[i] != prefix[i]) return false;
    }
    return true;
  }

  public static boolean inRange(byte[] key, byte[] begin, byte[] end) {
    return compare(key, begin) >= 0 && compare(key, end) < 0;
  }
}
