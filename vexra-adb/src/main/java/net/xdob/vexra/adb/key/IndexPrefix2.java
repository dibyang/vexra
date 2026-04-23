package net.xdob.vexra.adb.key;

import net.xdob.vexra.adb.db.KeyType;

import java.nio.ByteBuffer;

public final class IndexPrefix2 extends PrefixKey {
  private static final int OFFSET_INDEX_ID = HEADER_SIZE;


  private final int indexId;
  private final byte[] index;

  IndexPrefix2(byte[] data) {
    super(data);
    if (data.length < HEADER_SIZE + 4) {
      throw new IllegalArgumentException("Invalid IndexKey bytes, length=" + data.length);
    }
    ByteBuffer wrap = ByteBuffer.wrap(this.data);
    this.indexId = wrap.getInt(OFFSET_INDEX_ID);
    int indexOffset = HEADER_SIZE + 4;
    int indexLen = this.data.length -indexOffset;
    this.index = new byte[indexLen];
    System.arraycopy(this.data, indexOffset, this.index, 0, indexLen);
  }


  public int getIndexId() {
    return indexId;
  }

  public byte[] getIndex() {
    return index.clone();
  }

  public static IndexPrefix2 fromBytes(byte[] data) {
    return new IndexPrefix2(data);
  }

  public static IndexPrefix2 of(TabId tId, int indexId, byte[] index) {
    byte[] data = new byte[HEADER_SIZE + 4 + index.length];
    ByteBuffer wrap = ByteBuffer.wrap(data);
    wrap.putInt(tId.id);
    wrap.putLong(tId.epoch);
    wrap.put(KeyType.INDEX.getCode());
    wrap.putInt(indexId);
    wrap.put(index);
    return new IndexPrefix2(data);
  }
}
