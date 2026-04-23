package net.xdob.vexra.adb.key;

import net.xdob.vexra.adb.db.KeyType;

import java.nio.ByteBuffer;

public final class RowKey extends DataKey {
  private static final int OFFSET_ROW_ID = HEADER_SIZE;
  private static final int KEY_SIZE = HEADER_SIZE + 8;

  private final long rowId;

  RowKey(byte[] data) {
    super(data);
    if (data.length != KEY_SIZE) {
      throw new IllegalArgumentException("Invalid RowKey bytes, length=" + data.length);
    }
    ByteBuffer wrap = ByteBuffer.wrap(this.data);
    this.rowId = flipSign(wrap.getLong(OFFSET_ROW_ID));
  }

  public long getRowId() {
    return rowId;
  }

  public static RowKey of(TabId tId, long rowId) {
    byte[] data = new byte[KEY_SIZE];
    ByteBuffer wrap = ByteBuffer.wrap(data);
    wrap.putInt(OFFSET_TABLE_ID, tId.id);
    wrap.putLong(OFFSET_EPOCH, tId.epoch);
    wrap.put(OFFSET_TYPE, KeyType.ROW.getCode());
    wrap.putLong(OFFSET_ROW_ID, flipSign(rowId));
    return new RowKey(data);
  }
}
