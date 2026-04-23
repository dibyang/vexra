package net.xdob.vexra.adb.key;

import net.xdob.vexra.adb.db.DynamicByteBuffer;
import net.xdob.vexra.adb.db.KeyType;

import java.nio.ByteBuffer;

public class VersionRowCountDeltaKey extends RowCountDeltaKey {
  protected static final int HEADER_SIZE = 22;
  private final long commitTs;
  VersionRowCountDeltaKey(byte[] data) {
    super(data);
    if (this.data.length != HEADER_SIZE) {
      throw new IllegalArgumentException("Invalid VersionRowCountDeltaKey bytes, length=" + this.data.length);
    }
    ByteBuffer wrap = ByteBuffer.wrap(this.data);
    this.commitTs = Key.flipSign(wrap.getLong(HEADER_SIZE-8));
  }

  public static VersionRowCountDeltaKey fromBytes(byte[] data) {
    return new VersionRowCountDeltaKey(data);
  }


  public long getCommitTs() {
    return commitTs;
  }

  public static VersionRowCountDeltaKey of(TabId tId, long commitTs) {
    DynamicByteBuffer b =  DynamicByteBuffer.c();
    b.put(KeyType.META.getCode());
    b.put(MetaType.TABLE_ROW_COUNT_DELTA.getCode());
    b.putInt(tId.id);
    b.putLong(tId.epoch);
    b.putLong(Key.flipSign(commitTs));
    return new VersionRowCountDeltaKey(b.toArray());
  }

}
