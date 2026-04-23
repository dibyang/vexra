package net.xdob.vexra.adb.db;

import java.nio.ByteBuffer;

public class RowValue {
  public long txnId;     // 始终表示写入事务
  public long commitTs;  // 0 = 未提交
  public boolean deleted;
  public byte[] payload; // 行数据，可能为空
  public long rowKey; //row id不持久化

  // -------------------- 编码/解码 --------------------
  public static byte[] encodeValue(RowValue value) {
    DynamicByteBuffer buffer = DynamicByteBuffer.c();
    buffer.putLong(value.txnId);
    buffer.putLong(value.commitTs);
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
}
