package net.xdob.vexra.adb.key;

import net.xdob.vexra.adb.db.KeyType;

/**
 * ADB 表行的逻辑主键。
 *
 * <p>该 key 是 row 级 MVCC version key 的前缀，热路径会频繁用它定位某一行的版本链。
 * 类内部保持不可变语义，对外只返回新编码的字节数组，避免调用方修改内部 key 数据。</p>
 */
public final class RowKey extends DataKey {
  private static final int OFFSET_ROW_ID = HEADER_SIZE;
  private static final int KEY_SIZE = HEADER_SIZE + 8;

  private final long rowId;

  RowKey(byte[] data) {
    super(data);
    if (data.length != KEY_SIZE) {
      throw new IllegalArgumentException("Invalid RowKey bytes, length=" + data.length);
    }
    this.rowId = flipSign(readLong(this.data, OFFSET_ROW_ID));
  }

  public long getRowId() {
    return rowId;
  }

  /**
   * 创建指定表和 rowId 的逻辑 row key。
   *
   * @param tId 表 id 与 epoch
   * @param rowId 逻辑行 id
   * @return 新的 row key
   */
  public static RowKey of(TabId tId, long rowId) {
    return new RowKey(encode(tId.id, tId.epoch, rowId));
  }

  /**
   * 编码 row 版本扫描前缀。
   *
   * <p>row 的 committed/intent version key 以 RowKey 的固定布局作为前缀。
   * 点查可见性扫描只需要这个前缀定位版本链；直接按字段重新编码可以避开
   * {@link Key#toBytes()} 的 {@code Arrays.copyOf(...)} 调用，同时不暴露内部数组。</p>
   *
   * @return 可作为 version scan lower bound 的 row key 字节
   */
  public byte[] versionScanPrefixBytes() {
    return encode(tableId, epoch, rowId);
  }

  private static byte[] encode(int tableId, long epoch, long rowId) {
    byte[] data = new byte[KEY_SIZE];
    putInt(data, OFFSET_TABLE_ID, tableId);
    putLong(data, OFFSET_EPOCH, epoch);
    data[OFFSET_TYPE] = KeyType.ROW.getCode();
    putLong(data, OFFSET_ROW_ID, flipSign(rowId));
    return data;
  }

  private static long readLong(byte[] data, int offset) {
    return ((long) (data[offset] & 0xFF) << 56)
        | ((long) (data[offset + 1] & 0xFF) << 48)
        | ((long) (data[offset + 2] & 0xFF) << 40)
        | ((long) (data[offset + 3] & 0xFF) << 32)
        | ((long) (data[offset + 4] & 0xFF) << 24)
        | ((long) (data[offset + 5] & 0xFF) << 16)
        | ((long) (data[offset + 6] & 0xFF) << 8)
        | ((long) (data[offset + 7] & 0xFF));
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
