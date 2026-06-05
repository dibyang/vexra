package net.xdob.vexra.adb.key;

import net.xdob.vexra.adb.db.KeyType;

import java.nio.ByteBuffer;

public final class RowPrefix extends PrefixKey {

  RowPrefix(byte[] data) {
    super(data);
  }

  public static RowPrefix of(TabId tId) {
    byte[] data = new byte[HEADER_SIZE];
    ByteBuffer wrap = ByteBuffer.wrap(data);
    wrap.putInt(OFFSET_TABLE_ID, tId.id);
    wrap.putLong(OFFSET_EPOCH, tId.epoch);
    wrap.put(OFFSET_TYPE, KeyType.ROW.getCode());
    return new RowPrefix(data);
  }

  public static RowPrefix fromBytes(byte[] data) {
    return new RowPrefix(data);
  }
}
