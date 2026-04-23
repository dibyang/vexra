package net.xdob.vexra.adb.key;

import net.xdob.vexra.adb.db.DynamicByteBuffer;
import net.xdob.vexra.adb.db.KeyType;

import java.nio.ByteBuffer;

public class TableNextRowIdKey extends Key{
  protected static final int HEADER_SIZE = 10;
  protected final KeyType type;
  protected final MetaType metaType;
  protected final long tableId;
  TableNextRowIdKey(byte[] data) {
    super(data);
    if (this.data.length != HEADER_SIZE) {
      throw new IllegalArgumentException("Invalid TableStatsKey bytes, length=" + this.data.length);
    }

    ByteBuffer wrap = ByteBuffer.wrap(this.data);
    this.type = KeyType.getByCode(wrap.get());
    if (this.type != KeyType.META) {
      throw new IllegalArgumentException("Not a meta key, type=" + this.type);
    }
    this.metaType = MetaType.getByCode(wrap.get());
    if (this.metaType != MetaType.TABLE_NEXT_ROW_ID) {
      throw new IllegalArgumentException("Not a TableNextRowIdKey key, metaType=" + this.metaType);
    }

    this.tableId = wrap.getLong();
  }

  public KeyType getType() {
    return type;
  }

  public MetaType getMetaType() {
    return metaType;
  }

  public long getTableId() {
    return tableId;
  }



  public static TableNextRowIdKey of(long tableId) {
    DynamicByteBuffer b =  DynamicByteBuffer.c();
    b.put(KeyType.META.getCode());
    b.put(MetaType.TABLE_NEXT_ROW_ID.getCode());
    b.putLong(tableId);
    return new TableNextRowIdKey(b.toArray());
  }

}
