package net.xdob.vexra.adb.key;


public enum TxnKeyType {
  WRITE_REF(1),
  UNDO(2),
  SAVEPOINT(3);

  private final byte code;
  TxnKeyType(int c) {
    this.code = (byte) c;
  }
  public byte getCode() {
    return code;
  }
  public static TxnKeyType getByCode(byte code) {
    for (TxnKeyType type : values()) {
      if (type.code == code) {
        return type;
      }
    }
    throw new IllegalArgumentException("Unknown TxnKeyType code: " + code);
  }
}
