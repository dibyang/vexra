package net.xdob.vexra.adb.key;

import net.xdob.vexra.adb.db.DynamicByteBuffer;

import java.nio.ByteBuffer;

public final class TxnRefPrefix extends  Key{
  private final long txnId;
  private final TxnKeyType type;
  TxnRefPrefix(byte[] data) {
    super(data);
    if (this.data.length != 9) {
      throw new IllegalArgumentException("Invalid TxnRefKey bytes, length=" + this.data.length);
    }
    ByteBuffer wrap = ByteBuffer.wrap(this.data);
    txnId = flipSign(wrap.getLong());
    type = TxnKeyType.getByCode(wrap.get());
  }

  public long getTxnId() {
    return txnId;
  }

  public TxnKeyType getType() {
    return type;
  }


  public static TxnRefPrefix fromBytes(byte[] data) {
    return new TxnRefPrefix(data);
  }

  public static TxnRefPrefix of(long txnId, TxnKeyType type) {
    DynamicByteBuffer buffer = DynamicByteBuffer.c();
    buffer.putLong(flipSign(txnId));
    buffer.put(type.getCode());
    return new TxnRefPrefix(buffer.toArray());
  }
}
