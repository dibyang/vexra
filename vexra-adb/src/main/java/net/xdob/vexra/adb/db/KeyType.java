package net.xdob.vexra.adb.db;

public enum KeyType {
  ROW(1),// 已提交行版本
  INDEX(2), // 二级索引版本
  PK(3),// 主键索引版本（可选）
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
