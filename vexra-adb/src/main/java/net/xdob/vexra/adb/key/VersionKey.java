package net.xdob.vexra.adb.key;

import net.xdob.vexra.adb.db.KeyType;

import java.nio.ByteBuffer;

public abstract class VersionKey extends TableKey{
  VersionKey(byte[] data) {
    super(data);
  }



  public DataKey toDataKey() {
    byte[] bytes = new byte[data.length-9];
    System.arraycopy(data, 0, bytes, 0, bytes.length);
    return DataKey.fromBytes(bytes);
  }

  public static VersionKey fromBytes(byte[] data) {
    ByteBuffer wrap = ByteBuffer.wrap(data);
    KeyType type = KeyType.getByCode(wrap.get(OFFSET_TYPE));
    if(KeyType.ROW.equals( type)){
      return new VersionRowKey(data);
    }
    if(KeyType.INDEX.equals( type)){
      return new VersionIndexKey(data);
    }
    throw new IllegalArgumentException("Invalid VersionKey bytes, length=" + data.length);
  }

  public static VersionKey of(DataKey  key, boolean commited, long version) {
    if(key instanceof RowKey){
      return VersionRowKey.of(key.getTabID(), ((RowKey)key).getRowId(),commited, version);
    }
    if(key instanceof IndexKey){
      return VersionIndexKey.of( key.getTabID(), ((IndexKey)key).getIndexId(), ((IndexKey)key).getIndex(), ((IndexKey)key).getRowId(), commited, version);
    }
    throw new IllegalArgumentException("Unsupported key type: " + key.getClass().getName());
  }

  public static VersionKey of(VersionKey  key, boolean commited, long version) {
    if(key instanceof VersionRowKey){
      return VersionRowKey.of(key.getTabID(), ((VersionRowKey)key).getRowId(),commited, version);
    }
    if(key instanceof VersionIndexKey){
      return VersionIndexKey.of( key.getTabID(),((VersionIndexKey)key).getIndexId(), ((VersionIndexKey)key).getIndex(), ((VersionIndexKey)key).getRowId(), commited, version);
    }
    throw new IllegalArgumentException("Unsupported key type: " + key.getClass().getName());
  }

  public boolean isIndex() {
    return getType() == KeyType.INDEX;
  }

  public boolean isRow() {
    return getType() == KeyType.ROW;
  }

  public abstract boolean isCommited();

  public abstract long getRowId();

  public abstract long getVersion();
  public abstract long getTxnId();
  public abstract long getCommitTs();
}
