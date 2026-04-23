package net.xdob.vexra.adb.key;

import net.xdob.vexra.adb.db.DynamicByteBuffer;
import net.xdob.vexra.adb.db.KeyType;

import java.nio.ByteBuffer;

public class VersionRowCountKey extends RowCountKey {
  protected static final int HEADER_SIZE = 22;
  private final long commitTs;
  VersionRowCountKey(byte[] data) {
    super(data);
    if (this.data.length != HEADER_SIZE) {
      throw new IllegalArgumentException("Invalid VersionRowCountKey bytes, length=" + this.data.length);
    }
    ByteBuffer wrap = ByteBuffer.wrap(this.data);
    this.commitTs = Long.MAX_VALUE - Key.flipSign(wrap.getLong(HEADER_SIZE-8));
  }

  public static VersionRowCountKey fromBytes(byte[] data) {
    return new VersionRowCountKey(data);
  }


  public long getCommitTs() {
    return commitTs;
  }

  public static VersionRowCountKey of(TabId tId, long commitTs) {
    DynamicByteBuffer b =  DynamicByteBuffer.c();
    b.put(KeyType.META.getCode());
    b.put(MetaType.TABLE_ROW_COUNT.getCode());
    b.putInt(tId.id);
    b.putLong(tId.epoch);
    b.putLong(Key.flipSign(Long.MAX_VALUE-commitTs));
    return new VersionRowCountKey(b.toArray());
  }

}
