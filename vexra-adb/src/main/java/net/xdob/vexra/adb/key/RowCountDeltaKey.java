package net.xdob.vexra.adb.key;

import net.xdob.vexra.adb.db.DynamicByteBuffer;
import net.xdob.vexra.adb.db.KeyType;

import java.nio.ByteBuffer;

public class RowCountDeltaKey extends Key{
  protected static final int HEADER_SIZE = 14;
  protected final KeyType type;
  protected final MetaType metaType;
  protected final int tableId;
  protected final long epoch;
  RowCountDeltaKey(byte[] data) {
    super(data);
    if (this.data.length < HEADER_SIZE) {
      throw new IllegalArgumentException("Invalid RowCountDeltaKey bytes, length=" + this.data.length);
    }

    ByteBuffer wrap = ByteBuffer.wrap(this.data);
    this.type = KeyType.getByCode(wrap.get());
    if (this.type != KeyType.META) {
      throw new IllegalArgumentException("Not a meta key, type=" + this.type);
    }
    this.metaType = MetaType.getByCode(wrap.get());
    if (this.metaType != MetaType.TABLE_ROW_COUNT_DELTA) {
      throw new IllegalArgumentException("Not a RowCountDeltaKey key, metaType=" + this.metaType);
    }

    this.tableId = wrap.getInt();
    this.epoch = wrap.getLong();
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

  public TabId getTabKey() {
    return TabId.of(tableId, epoch);
  }

  public static RowCountDeltaKey fromBytes(byte[] data) {
    return new RowCountDeltaKey(data);
  }

  public static RowCountDeltaKey of(TabId tId) {
    DynamicByteBuffer b =  DynamicByteBuffer.c();
    b.put(KeyType.META.getCode());
    b.put(MetaType.TABLE_ROW_COUNT_DELTA.getCode());
    b.putInt(tId.id);
    b.putLong(tId.epoch);
    return new RowCountDeltaKey(b.toArray());
  }

}
