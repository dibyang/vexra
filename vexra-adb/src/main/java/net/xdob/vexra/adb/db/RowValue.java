package net.xdob.vexra.adb.db;

public class RowValue {
  private static final int OFFSET_TXN_ID = 0;
  private static final int OFFSET_COMMIT_TS = 8;
  private static final int OFFSET_DELETED = 16;
  private static final int OFFSET_PAYLOAD_LENGTH = 17;
  private static final int OFFSET_PAYLOAD = 21;
  private static final byte[] EMPTY_PAYLOAD = new byte[0];
  static final int COUNTABLE_INVALID = 0;
  static final int COUNTABLE_ROW = 1;
  static final int COUNTABLE_NOT_ROW = 2;

  public long txnId;     // 濮嬬粓琛ㄧず鍐欏叆浜嬪姟
  public long commitTs;  // 0 = 鏈彁浜?
  public boolean deleted;
  public byte[] payload; // 琛屾暟鎹紝鍙兘涓虹┖
  public long rowKey; //row id涓嶆寔涔呭寲

  // -------------------- 缂栫爜/瑙ｇ爜 --------------------
  public static byte[] encodeValue(RowValue value) {
    return encodeValue(value, value.commitTs);
  }

  /**
   * 使用指定 commitTs 编码 row value，但不修改传入对象。
   *
   * <p>本地 commit 写 batch 会把事务本地 write set 中的未提交 RowValue 写成 committed
   * version。该路径只需要落盘字节里的 commitTs 变为真实提交时间，不需要为每一行复制一个
   * 临时 RowValue 对象；传入对象仍保持事务内状态，提交失败时可以继续用于重试或回滚。</p>
   *
   * @param value 待编码的 row value
   * @param commitTs 写入字节中的提交时间戳
   * @return 可持久化的 row value 字节
   */
  public static byte[] encodeValue(RowValue value, long commitTs) {
    DynamicByteBuffer buffer = DynamicByteBuffer.c();
    buffer.putLong(value.txnId);
    buffer.putLong(commitTs);
    buffer.put(value.deleted ? (byte) 1 : (byte) 0);
    buffer.putBytesWithLength(value.payload);
    return buffer.toArray();
  }

  public static RowValue decodeValue(byte[] data) {
    if(data==null||data.length==0){
      return null;
    }
    RowValue value = new RowValue();
    value.txnId = readLong(data, OFFSET_TXN_ID);
    value.commitTs = readLong(data, OFFSET_COMMIT_TS);
    value.deleted = data[OFFSET_DELETED] != 0;
    int len = readInt(data, OFFSET_PAYLOAD_LENGTH);
    if (len > 0) {
      byte[] bytes = new byte[len];
      System.arraycopy(data, OFFSET_PAYLOAD, bytes, 0, len);
      value.payload = bytes;
    } else {
      value.payload = EMPTY_PAYLOAD;
    }
    return value;
  }

  /**
   * 只解码行值头部元数据，不复制 payload。
   *
   * <p>COUNT、可见性判断等只关心提交时间、删除标记和 payload 是否存在的路径可以使用它，
   * 避免为每一行分配并复制完整 payload 字节数组。</p>
   */
  public static Metadata decodeMetadata(byte[] data) {
    if(data==null||data.length==0){
      return null;
    }
    Metadata metadata = new Metadata();
    metadata.txnId = readLong(data, OFFSET_TXN_ID);
    metadata.commitTs = readLong(data, OFFSET_COMMIT_TS);
    metadata.deleted = data[OFFSET_DELETED] != 0;
    metadata.payloadLength = readInt(data, OFFSET_PAYLOAD_LENGTH);
    return metadata;
  }

  /**
   * 只判断 encoded row value 是否可被 COUNT 计入，不创建 metadata 对象。
   *
   * <p>range count 只关心 value 是否有效、是否删除、payload 是否存在。该入口复用固定磁盘
   * offset，避免在每个候选 committed 版本上分配 {@link Metadata}。返回三态而不是 boolean，
   * 是为了保留旧逻辑中“无效 value 继续尝试更旧版本”的行为。</p>
   *
   * @param data encoded row value
   * @return {@link #COUNTABLE_INVALID}、{@link #COUNTABLE_ROW} 或
   *     {@link #COUNTABLE_NOT_ROW}
   */
  static int countableState(byte[] data) {
    if (data == null || data.length == 0) {
      return COUNTABLE_INVALID;
    }
    if (data[OFFSET_DELETED] != 0) {
      return COUNTABLE_NOT_ROW;
    }
    return readInt(data, OFFSET_PAYLOAD_LENGTH) > 0
        ? COUNTABLE_ROW : COUNTABLE_NOT_ROW;
  }

  static long commitTs(byte[] data) {
    return readLong(data, OFFSET_COMMIT_TS);
  }

  static boolean isDeleted(byte[] data) {
    return data[OFFSET_DELETED] != 0;
  }

  static int payloadLength(byte[] data) {
    return readInt(data, OFFSET_PAYLOAD_LENGTH);
  }

  static int payloadOffset() {
    return OFFSET_PAYLOAD;
  }

  private static long readLong(byte[] data, int offset) {
    return ((long) (data[offset] & 0xff) << 56)
        | ((long) (data[offset + 1] & 0xff) << 48)
        | ((long) (data[offset + 2] & 0xff) << 40)
        | ((long) (data[offset + 3] & 0xff) << 32)
        | ((long) (data[offset + 4] & 0xff) << 24)
        | ((long) (data[offset + 5] & 0xff) << 16)
        | ((long) (data[offset + 6] & 0xff) << 8)
        | (long) (data[offset + 7] & 0xff);
  }

  private static int readInt(byte[] data, int offset) {
    return ((data[offset] & 0xff) << 24)
        | ((data[offset + 1] & 0xff) << 16)
        | ((data[offset + 2] & 0xff) << 8)
        | (data[offset + 3] & 0xff);
  }

  /**
   * RowValue 的轻量元数据视图。
   *
   * <p>该对象不持有 payload 内容，不能用于返回行数据，只能用于可见性和计数类判断。</p>
   */
  public static final class Metadata {
    public long txnId;
    public long commitTs;
    public boolean deleted;
    public int payloadLength;

    /**
     * 判断该版本是否包含可计数的行 payload。
     *
     * @return payload 长度大于 0 时返回 true
     */
    public boolean hasPayload() {
      return payloadLength > 0;
    }
  }
}
