package net.xdob.vexra.adb.key;

import net.xdob.vexra.adb.db.KeyType;

import java.nio.ByteBuffer;

public final class VersionRowKey extends VersionKey {
  private static final int OFFSET_ROW_ID = HEADER_SIZE;
  private static final int OFFSET_COMMITED = HEADER_SIZE + 8;
  private static final int OFFSET_VERSION = OFFSET_COMMITED + 1;
  private static final int KEY_SIZE = HEADER_SIZE + 17;

  private final long rowId;
  private final boolean commited;
  private final long version;

  VersionRowKey(byte[] data) {
    super(data);
    if (data.length != KEY_SIZE) {
      throw new IllegalArgumentException("Invalid VersionRowKey bytes, length=" + data.length);
    }
    ByteBuffer wrap = ByteBuffer.wrap(this.data);
    this.rowId = flipSign(wrap.getLong(OFFSET_ROW_ID));
    this.commited = wrap.get(OFFSET_COMMITED) == 1;
    this.version = flipSign(wrap.getLong(OFFSET_VERSION));
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

  public static VersionRowKey of(TabId tId, long rowId, boolean commited, long version) {
    byte[] data = new byte[KEY_SIZE];
    ByteBuffer wrap = ByteBuffer.wrap(data);
    wrap.putInt(OFFSET_TABLE_ID, tId.id);
    wrap.putLong(OFFSET_EPOCH, tId.epoch);
    wrap.put(OFFSET_TYPE, KeyType.ROW.getCode());
    wrap.putLong(OFFSET_ROW_ID, flipSign(rowId));
    wrap.put(OFFSET_COMMITED, commited ? (byte) 1 : 0);
    wrap.putLong(OFFSET_VERSION, flipSign(Long.MAX_VALUE - version));
    return new VersionRowKey(data);
  }
}
