package net.xdob.vexra.adb.key;

import net.xdob.vexra.adb.db.KeyType;

import java.nio.ByteBuffer;

public final class IndexStatusKey extends Key {
  protected static final int HEADER_SIZE = 18;

  protected final KeyType type;
  protected final MetaType metaType;
  protected final int tableId;
  protected final long epoch;
  protected final int indexId;

  IndexStatusKey(byte[] data) {
    super(data);
    if (this.data.length != HEADER_SIZE) {
      throw new IllegalArgumentException("Invalid IndexStatusKey bytes, length=" + this.data.length);
    }

    ByteBuffer wrap = ByteBuffer.wrap(this.data);

    this.type = KeyType.getByCode(wrap.get());
    if (this.type != KeyType.META) {
      throw new IllegalArgumentException("Not a meta key, type=" + this.type);
    }

    this.metaType = MetaType.getByCode(wrap.get());
    if (this.metaType != MetaType.INDEX_STATUS) {
      throw new IllegalArgumentException("Not an INDEX_STATUS, metaType=" + this.metaType);
    }

    this.tableId = wrap.getInt();
    this.epoch = wrap.getLong();
    this.indexId = wrap.getInt();
  }

  public KeyType getType() {
    return type;
  }

  public MetaType getMetaType() {
    return metaType;
  }

  public int getTableId() {
    return tableId;
  }

  public long getEpoch() {
    return epoch;
  }

  public int getIndexId() {
    return indexId;
  }

  public static IndexStatusKey of(TabId tId, int indexId) {
    byte[] data = new byte[HEADER_SIZE];
    ByteBuffer wrap = ByteBuffer.wrap(data);
    wrap.put(KeyType.META.getCode());
    wrap.put(MetaType.INDEX_STATUS.getCode());
    wrap.putInt(tId.id);
    wrap.putLong(tId.epoch);
    wrap.putInt(indexId);
    return new IndexStatusKey(data);
  }
}
