package net.xdob.vexra.adb.key;

import net.xdob.vexra.adb.db.DynamicByteBuffer;

import java.nio.ByteBuffer;

public final class TxnRefKey extends  Key{
  public static final int HEAD_SIZE = 10;
  private final long txnId;
  private final TxnKeyType type;
  // 真实key存在哪一个CF
  private final byte cfId;
  private final VersionKey key;
  TxnRefKey(byte[] data) {
    super(data);
    if (this.data.length <= HEAD_SIZE) {
      throw new IllegalArgumentException("Invalid TxnRefKey bytes, length=" + this.data.length);
    }
    ByteBuffer wrap = ByteBuffer.wrap(this.data);
    txnId = flipSign(wrap.getLong());
    type = TxnKeyType.getByCode(wrap.get());
    cfId = wrap.get();
    byte[] bytes = new byte[this.data.length - HEAD_SIZE];
    System.arraycopy(this.data, HEAD_SIZE, bytes, 0, bytes.length);
    key = VersionKey.fromBytes(bytes);
  }

  public long getTxnId() {
    return txnId;
  }

  public TxnKeyType getType() {
    return type;
  }

  /**
   * 获取真实key存在哪一个CF
   */
  public byte getCfId() {
    return cfId;
  }

  /**
   * 获取真实key
   */
  public VersionKey getKey() {
    return key;
  }

  public static TxnRefKey fromBytes(byte[] data) {
    return new TxnRefKey(data);
  }

  public static TxnRefKey of(long txnId, TxnKeyType type, byte cfId, VersionKey key) {
    DynamicByteBuffer buffer = DynamicByteBuffer.c();
    buffer.putLong(flipSign(txnId));
    buffer.put(type.getCode());
    buffer.put(cfId);
    buffer.put(key.toBytes());
    return new TxnRefKey(buffer.toArray());
  }
}
