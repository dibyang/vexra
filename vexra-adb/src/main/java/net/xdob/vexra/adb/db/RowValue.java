package net.xdob.vexra.adb.db;

import java.nio.ByteBuffer;

public class RowValue {
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
    ByteBuffer buffer = ByteBuffer.wrap(data);
    RowValue value = new RowValue();
    value.txnId = buffer.getLong();
    value.commitTs = buffer.getLong();
    value.deleted = buffer.get() != 0;
    int len = buffer.getInt();
    if (len > 0) {
      byte[] bytes = new byte[len];
      buffer.get(bytes);
      value.payload = bytes;
    } else {
      value.payload = new byte[0];
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
    ByteBuffer buffer = ByteBuffer.wrap(data);
    Metadata metadata = new Metadata();
    metadata.txnId = buffer.getLong();
    metadata.commitTs = buffer.getLong();
    metadata.deleted = buffer.get() != 0;
    metadata.payloadLength = buffer.getInt();
    return metadata;
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
