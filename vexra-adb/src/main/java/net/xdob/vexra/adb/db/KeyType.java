package net.xdob.vexra.adb.db;

public enum KeyType {
  ROW(1),// 宸叉彁浜よ鐗堟湰
  INDEX(2), // 浜岀骇绱㈠紩鐗堟湰
  PK(3),// 涓婚敭绱㈠紩鐗堟湰锛堝彲閫夛級
  META(16),
  COUNT(20)
  ;

  private final byte code;
  KeyType(int c) {
    this.code = (byte) c;
  }
  public byte getCode() {
    return code;
  }
  public static KeyType getByCode(byte code) {
    for (KeyType type : values()) {
      if (type.code == code) {
        return type;
      }
    }
    throw new IllegalArgumentException("Unknown KeyType code: " + code);
  }
}
