package net.xdob.vexra.adb.key;

import net.xdob.vexra.adb.db.KeyType;

import java.nio.ByteBuffer;

public class TxnNextIdKey extends Key{
  protected static final int HEADER_SIZE = 2;
  public static final MetaType META_TYPE = MetaType.TXN_NEXT_ID;
  protected final KeyType type;
  private final MetaType metaType;
  TxnNextIdKey(byte[] data) {
    super(data);
    if (this.data.length < HEADER_SIZE) {
      throw new IllegalArgumentException("Invalid TxnNextIdKey bytes, length=" + this.data.length);
    }
    ByteBuffer wrap = ByteBuffer.wrap(this.data);
    this.type = KeyType.getByCode(wrap.get());
    if (this.type != KeyType.META) {
      throw new IllegalArgumentException("Not a META, type=" + this.type);
    }
    this.metaType = MetaType.getByCode(wrap.get());
    if (this.metaType != META_TYPE) {
      throw new IllegalArgumentException("Not a TXN_NEXT_ID key, type=" + this.type);
    }

  }

  public KeyType getType() {
    return type;
  }

  public MetaType getMetaType() {
    return metaType;
  }

  public static TxnNextIdKey of() {
    ByteBuffer b =  ByteBuffer.allocate(HEADER_SIZE);
    b.put(KeyType.META.getCode());
    b.put(META_TYPE.getCode());
    return new TxnNextIdKey(b.array());
  }

}
