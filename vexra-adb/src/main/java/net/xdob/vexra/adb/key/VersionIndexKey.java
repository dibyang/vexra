package net.xdob.vexra.adb.key;

import net.xdob.vexra.adb.db.KeyType;

import java.nio.ByteBuffer;

public final class VersionIndexKey extends VersionKey {
  private static final int OFFSET_INDEX_ID = HEADER_SIZE;


  private final int indexId;
  private final byte[] index;
  private final long rowId;
  private final boolean commited;
  private final long version;

  VersionIndexKey(byte[] data) {
    super(data);
    if (data.length < HEADER_SIZE + 21) {
      throw new IllegalArgumentException("Invalid VersionIndexKey bytes, length=" + data.length);
    }
    ByteBuffer wrap = ByteBuffer.wrap(this.data);
    this.indexId = wrap.getInt(OFFSET_INDEX_ID);
    int indexLen = this.data.length - HEADER_SIZE - 21;
    this.index = new byte[indexLen];
    int indexOffset = HEADER_SIZE + 4;
    System.arraycopy(this.data, indexOffset, this.index, 0, indexLen);
    int rowIdOffset = indexOffset + indexLen;
    this.rowId = flipSign(wrap.getLong(rowIdOffset));
    this.commited = wrap.get(rowIdOffset+8) == 1;
    this.version = flipSign(wrap.getLong(rowIdOffset+9));
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

  public long getVersion() {
    return version;
  }

  @Override
  public boolean isCommited() {
    return commited;
  }

  public long getTxnId() {
    if (isCommited()) {
      throw new IllegalStateException("Not INDEX_INTENT: " + getType());
    }
    return version;
  }

  public long getCommitTs() {
    if (!isCommited()) {
      throw new IllegalStateException("Not committed INDEX: " + getType());
    }
    return version;
  }

  public static VersionIndexKey of(TabId tId, int indexId, byte[] index, long rowId, boolean commited, long version) {
    byte[] data = new byte[HEADER_SIZE + 21 + index.length];
    ByteBuffer wrap = ByteBuffer.wrap(data);

    wrap.putInt(tId.id);
    wrap.putLong(tId.epoch);
    wrap.put(KeyType.INDEX.getCode());
    wrap.putInt(indexId);
    wrap.put(index);
    wrap.putLong(flipSign(rowId));
    wrap.put(commited? (byte)1 : 0);
    wrap.putLong(flipSign(version));

    return new VersionIndexKey(data);
  }
}
