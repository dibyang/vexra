package net.xdob.vexra.adb.key;

import net.xdob.vexra.adb.db.KeyType;

import java.nio.ByteBuffer;

public abstract class PrefixKey extends TableKey{
  PrefixKey(byte[] data) {
    super(data);
  }

  public static PrefixKey fromBytes(byte[] data) {
    ByteBuffer wrap = ByteBuffer.wrap(data);
    KeyType type = KeyType.getByCode(wrap.get(OFFSET_TYPE));
    if(KeyType.ROW.equals( type)){
      return RowPrefix.fromBytes(data);
    }
    if(KeyType.INDEX.equals( type)){
      return IndexPrefix.fromBytes(data);
    }
    throw new IllegalArgumentException("Invalid PrefixKey bytes, length=" + data.length);
  }

  public boolean isIndex() {
    return getType() == KeyType.INDEX;
  }

  public boolean isRow() {
    return getType() == KeyType.ROW;
  }
}
