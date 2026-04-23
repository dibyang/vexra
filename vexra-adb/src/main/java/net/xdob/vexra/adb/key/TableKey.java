package net.xdob.vexra.adb.key;

import net.xdob.vexra.adb.db.KeyType;

import java.nio.ByteBuffer;

public abstract class TableKey extends  Key{
  protected static final int OFFSET_TABLE_ID = 0;
  protected static final int OFFSET_EPOCH = 4;
  protected static final int OFFSET_TYPE = 12;
  public static final int HEADER_SIZE = 13;

  protected final int tableId;
  protected final long epoch;
  protected final KeyType type;
  TableKey(byte[] data) {
    super(data);
    if (data.length < HEADER_SIZE) {
      throw new IllegalArgumentException("Invalid TableKey bytes, length=" + data.length);
    }
    ByteBuffer wrap = ByteBuffer.wrap(data);
    this.tableId = wrap.getInt(OFFSET_TABLE_ID);
    this.epoch = wrap.getLong(OFFSET_EPOCH);
    this.type = KeyType.getByCode(wrap.get(OFFSET_TYPE));
  }


  public int getTableId() {
    return tableId;
  }

  public long getEpoch() {
    return epoch;
  }

  public KeyType getType() {
    return type;
  }

  public TabId getTabID() {
    return TabId.of(tableId, epoch);
  }

}
