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

  /**
   * 直接编码已提交的 row version key。
   *
   * <p>本地 commit 热路径只需要把已有 {@link RowKey} 转成 committed version
   * key 字节；构造完整 {@link VersionRowKey} 后再调用 {@code toBytes()} 会多分配一个
   * key 对象和一次防御性拷贝。该方法保持与 {@link #of(TabId, long, boolean, long)}
   * 完全相同的磁盘格式，只跳过临时对象。</p>
   *
   * @param rowKey 逻辑 row key
   * @param commitTs 提交时间戳
   * @return committed version key 字节
   */
  public static byte[] committedBytes(RowKey rowKey, long commitTs) {
    return committedBytes(rowKey.getTabID(), rowKey.getRowId(), commitTs);
  }

  public static byte[] committedBytes(TabId tabId, long rowId, long commitTs) {
    byte[] data = new byte[KEY_SIZE];
    putInt(data, OFFSET_TABLE_ID, tabId.id);
    putLong(data, OFFSET_EPOCH, tabId.epoch);
    data[OFFSET_TYPE] = KeyType.ROW.getCode();
    putLong(data, OFFSET_ROW_ID, flipSign(rowId));
    data[OFFSET_COMMITED] = 1;
    putLong(data, OFFSET_VERSION, flipSign(Long.MAX_VALUE - commitTs));
    return data;
  }

  private static void putInt(byte[] data, int offset, int value) {
    data[offset] = (byte) (value >>> 24);
    data[offset + 1] = (byte) (value >>> 16);
    data[offset + 2] = (byte) (value >>> 8);
    data[offset + 3] = (byte) value;
  }

  private static void putLong(byte[] data, int offset, long value) {
    data[offset] = (byte) (value >>> 56);
    data[offset + 1] = (byte) (value >>> 48);
    data[offset + 2] = (byte) (value >>> 40);
    data[offset + 3] = (byte) (value >>> 32);
    data[offset + 4] = (byte) (value >>> 24);
    data[offset + 5] = (byte) (value >>> 16);
    data[offset + 6] = (byte) (value >>> 8);
    data[offset + 7] = (byte) value;
  }
}
