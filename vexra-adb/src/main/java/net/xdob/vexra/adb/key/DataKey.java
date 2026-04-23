package net.xdob.vexra.adb.key;

import net.xdob.vexra.adb.db.KeyType;

import java.nio.ByteBuffer;
import java.util.Arrays;

public abstract class DataKey extends TableKey{
  DataKey(byte[] data) {
    super(data);
  }

  public static DataKey fromBytes(byte[] data) {
    ByteBuffer wrap = ByteBuffer.wrap(data);
    KeyType type = KeyType.getByCode(wrap.get(OFFSET_TYPE));
    if(KeyType.ROW.equals( type)){
      return new RowKey(data);
    }
    if(KeyType.INDEX.equals( type)){
      return new IndexKey(data);
    }
    throw new IllegalArgumentException("Invalid DataKey bytes, length=" + data.length);
  }

  public boolean isIndex() {
    return getType() == KeyType.INDEX;
  }

  public boolean isRow() {
    return getType() == KeyType.ROW;
  }

  public abstract long getRowId();

  @Override
  public String toString() {
    return "DataKey{" +
        "tableId=" + tableId +
        ", epoch=" + epoch +
        ", type=" + type +
        ", data=" + Arrays.toString(data) +
        '}';
  }
}
