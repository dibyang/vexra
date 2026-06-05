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
