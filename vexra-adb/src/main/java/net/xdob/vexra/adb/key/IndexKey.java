package net.xdob.vexra.adb.key;

import net.xdob.vexra.adb.db.KeyType;

import java.nio.ByteBuffer;

public final class IndexKey extends DataKey {
  private static final int OFFSET_INDEX_ID = HEADER_SIZE;


  private final int indexId;
  private final byte[] index;
  private final long rowId;

  IndexKey(byte[] data) {
    super(data);
    if (data.length < HEADER_SIZE + 12) {
      throw new IllegalArgumentException("Invalid IndexKey bytes, length=" + data.length);
    }
    ByteBuffer wrap = ByteBuffer.wrap(this.data);
    this.indexId = wrap.getInt(OFFSET_INDEX_ID);
    int indexLen = this.data.length - HEADER_SIZE - 12;
    this.index = new byte[indexLen];
    int indexOffset = HEADER_SIZE + 4;
    System.arraycopy(this.data, indexOffset, this.index, 0, indexLen);
    int rowIdOffset = indexOffset + indexLen;
    this.rowId = flipSign(wrap.getLong(rowIdOffset));
  }

  public int getIndexId() {
    return indexId;
  }

  public byte[] getIndex() {
    return index.clone();
  }

  public long getRowId() {
    return rowId;
  }


  public static IndexKey of(TabId tId, int indexId, byte[] index, long rowId) {
    byte[] data = new byte[HEADER_SIZE + 12 + index.length];
    ByteBuffer wrap = ByteBuffer.wrap(data);
    wrap.putInt(tId.id);
    wrap.putLong(tId.epoch);
    wrap.put(KeyType.INDEX.getCode());
    wrap.putInt(indexId);
    wrap.put(index);
    wrap.putLong(flipSign(rowId));
    return new IndexKey(data);
  }
}
