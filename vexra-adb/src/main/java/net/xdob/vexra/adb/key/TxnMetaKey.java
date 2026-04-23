package net.xdob.vexra.adb.key;

import net.xdob.vexra.adb.db.DynamicByteBuffer;
import net.xdob.vexra.adb.db.KeyType;

import java.nio.ByteBuffer;

public class TxnMetaKey extends Key{
  protected static final int OFFSET_TYPE = 0;
  protected static final int OFFSET_META_TYPE = 1;
  protected static final int OFFSET_META_LEN = 2;
  protected static final int HEADER_SIZE = 6;
  protected final KeyType type;
  private final MetaType metaType;
  private final byte[] meta;
  TxnMetaKey(byte[] data) {
    super(data);
    if (this.data.length < HEADER_SIZE) {
      throw new IllegalArgumentException("Invalid MetaKey bytes, length=" + this.data.length);
    }
    ByteBuffer wrap = ByteBuffer.wrap(this.data);
    this.type = KeyType.getByCode(wrap.get(OFFSET_TYPE));
    if (this.type != KeyType.META) {
      throw new IllegalArgumentException("Not a META key, type=" + this.type);
    }
    this.metaType = MetaType.getByCode(wrap.get(OFFSET_META_TYPE));
    if (this.metaType != MetaType.TXN_META) {
      throw new IllegalArgumentException("Not a TXN META key, type=" + this.type);
    }
    int len = wrap.getInt(OFFSET_META_LEN);
    if (len < 0 || HEADER_SIZE + len != this.data.length) {
      throw new IllegalArgumentException("Invalid MetaKey meta length, len=" + len + ", total=" + data.length);
    }
    if(len>0){
      byte[] bytes = new byte[len];
      wrap.position(HEADER_SIZE);
      wrap.get(bytes);
      this.meta = bytes;
    } else {
      this.meta = new byte[0];
    }
  }

  public KeyType getType() {
    return type;
  }

  public MetaType getMetaType() {
    return metaType;
  }

  public boolean hasMeta() {
    return meta.length>0;
  }

  public byte[] getMeta() {
    return meta.clone();
  }

  public static TxnMetaKey of(byte[] meta) {
    DynamicByteBuffer b =  DynamicByteBuffer.c();
    b.put(KeyType.META.getCode());
    b.put(MetaType.TXN_META.getCode());
    if(meta!=null&& meta.length>0) {
      b.putBytesWithLength(meta);
    }else{
      b.putInt(0);
    }
    return new TxnMetaKey(b.toArray());
  }

  public long getTxnId() {
    if (metaType != MetaType.TXN_META || meta.length != 8) {
      throw new IllegalStateException("Not TXN_META key");
    }
    return flipSign(ByteBuffer.wrap(meta).getLong());
  }

  public static TxnMetaKey txnMeta(long txnId) {
    ByteBuffer buf = ByteBuffer.allocate(8);
    buf.putLong(flipSign(txnId));
    return of(buf.array());
  }
}
