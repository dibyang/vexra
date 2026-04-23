package net.xdob.vexra.adb.key;

import java.util.Arrays;

public class Key {
  protected final byte[] data;
  protected final int hash;
  public Key(byte[] data) {
    this.data = data;
    this.hash = Arrays.hashCode(data);
  }

  public byte[] toBytes() {
    return  Arrays.copyOf(data, data.length);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Key)) return false;
    return Arrays.equals(data, ((Key) o).data);
  }

  @Override
  public int hashCode() {
    return hash;
  }

  public static long flipSign(long v) {
    return v ^ Long.MIN_VALUE;
  }
  /**
   * 符号位翻转
   */
  public static int flipSign(int v) {
    return v ^ Integer.MIN_VALUE;
  }

}
