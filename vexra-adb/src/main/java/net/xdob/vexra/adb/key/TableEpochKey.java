package net.xdob.vexra.adb.key;

import net.xdob.vexra.adb.db.KeyType;

import java.nio.ByteBuffer;

public final class TableEpochKey extends Key {
  static final int HEADER_SIZE = 6;

  final KeyType type;
  final MetaType metaType;
  final int tableId;

  TableEpochKey(byte[] data) {
    super(data);
    if (this.data.length != HEADER_SIZE) {
      throw new IllegalArgumentException("Invalid TableEpochKey bytes, length=" + this.data.length);
    }

    ByteBuffer wrap = ByteBuffer.wrap(this.data);

    this.type = KeyType.getByCode(wrap.get());
    if (this.type != KeyType.META) {
      throw new IllegalArgumentException("Not a meta key, type=" + this.type);
    }

    this.metaType = MetaType.getByCode(wrap.get());
    if (this.metaType != MetaType.TABLE_EPOCH) {
      throw new IllegalArgumentException("Not an TABLE_EPOCH, metaType=" + this.metaType);
    }
    this.tableId = wrap.getInt();
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

  public static TableEpochKey of(int tableId) {
    byte[] data = new byte[HEADER_SIZE];
    ByteBuffer wrap = ByteBuffer.wrap(data);
    wrap.put(KeyType.META.getCode());
    wrap.put(MetaType.TABLE_EPOCH.getCode());
    wrap.putInt(tableId);
    return new TableEpochKey(data);
  }
}
