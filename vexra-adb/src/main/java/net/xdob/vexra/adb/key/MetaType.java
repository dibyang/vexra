package net.xdob.vexra.adb.key;


public enum MetaType {
  TXN_META(1),        // 全局事务元数据
  TXN_NEXT_ID(2),
  TXN_COMMIT_TS(3),
  TABLE_META(11),      // 表定义/表级结构元数据
  TABLE_NEXT_ROW_ID(12),
  TABLE_ROW_COUNT_DELTA(13),     // 表记录数统计(本次事务的变化数)
  TABLE_ROW_COUNT(14),     // 表记录数统计(累加到某次事务后的数)
  TABLE_EPOCH(15),
  INDEX_META(31),      // 索引定义
  INDEX_STATUS(32),
  INDEX_STATS(33),     // 索引统计
  SYSTEM_META(50),     // 其他系统级元数据
  DATABASE_META(51)   // 数据库级元数据
  ;
  private final byte code;
  MetaType(int c) {
    this.code = (byte) c;
  }
  public byte getCode() {
    return code;
  }
  public static MetaType getByCode(byte code) {
    for (MetaType type : values()) {
      if (type.code == code) {
        return type;
      }
    }
    throw new IllegalArgumentException("Unknown MetaType code: " + code);
  }
}
