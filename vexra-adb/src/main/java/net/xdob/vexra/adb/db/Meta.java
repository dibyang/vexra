package net.xdob.vexra.adb.db;

public class Meta {
  private byte[] key;
  private byte[] value;

  public byte[] getKey() {
    return key;
  }

  public Meta setKey(byte[] key) {
    this.key = key;
    return this;
  }

  public byte[] getValue() {
    return value;
  }

  public Meta setValue(byte[] value) {
    this.value = value;
    return this;
  }

  public static Meta of(byte[] key, byte[] value) {
    return new Meta().setKey(key).setValue(value);
  }
}
