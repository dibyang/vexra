package net.xdob.vexra.adb.key;

import net.xdob.vexra.adb.db.KeyType;

import java.nio.ByteBuffer;

public final class IndexPrefix extends PrefixKey {
  private static final int OFFSET_INDEX_ID = HEADER_SIZE;


  private final int indexId;

  IndexPrefix(byte[] data) {
    super(data);
    if (data.length != HEADER_SIZE + 4) {
      throw new IllegalArgumentException("Invalid IndexPrefix bytes, length=" + data.length);
    }
    ByteBuffer wrap = ByteBuffer.wrap(this.data);
    this.indexId = wrap.getInt(OFFSET_INDEX_ID);

  }

  public int getIndexId() {
    return indexId;
  }



  public static IndexPrefix of(TabId tId, int indexId) {
    byte[] data = new byte[HEADER_SIZE + 4];
    ByteBuffer wrap = ByteBuffer.wrap(data);
    wrap.putInt(tId.id);
    wrap.putLong(tId.epoch);
    wrap.put(KeyType.INDEX.getCode());
    wrap.putInt(indexId);

    return new IndexPrefix(data);
  }
}
