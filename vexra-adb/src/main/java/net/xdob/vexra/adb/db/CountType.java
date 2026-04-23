package net.xdob.vexra.adb.db;

public enum CountType {
  ROW(1),
  PK(2),
  INDEX(3),
  META(4);

  private final byte code;
  CountType(int c) {
    this.code = (byte) c;
  }
  public byte getCode() {
    return code;
  }
  public static CountType getByCode(byte code) {
    for (CountType type : values()) {
      if (type.code == code) {
        return type;
      }
    }
    return null;
  }
}
