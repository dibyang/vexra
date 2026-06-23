package net.xdob.vexra.adb.key;

import net.xdob.vexra.adb.db.DynamicByteBuffer;
import net.xdob.vexra.adb.db.KeyType;

import java.nio.ByteBuffer;

/**
 * 带提交时间戳的 segment row-count delta META key。
 *
 * <p>同一个 segment 会保留多个提交版本，range count 按读事务 startTs 汇总
 * 不晚于快照的 delta，从而保持 MVCC 快照语义。该 key 只服务于可选统计路径，
 * 不影响现有 row version 的可见性判断。</p>
 */
public class VersionSegmentRowCountDeltaKey
    extends SegmentRowCountDeltaKey {
  protected static final int HEADER_SIZE = 30;
  private final long commitTs;

  VersionSegmentRowCountDeltaKey(byte[] data) {
    super(data);
    if (this.data.length != HEADER_SIZE) {
      throw new IllegalArgumentException(
          "Invalid VersionSegmentRowCountDeltaKey bytes, length="
              + this.data.length);
    }
    ByteBuffer wrap = ByteBuffer.wrap(this.data);
    this.commitTs = Key.flipSign(wrap.getLong(HEADER_SIZE - Long.BYTES));
  }

  public static VersionSegmentRowCountDeltaKey fromBytes(byte[] data) {
    return new VersionSegmentRowCountDeltaKey(data);
  }

  public long getCommitTs() {
    return commitTs;
  }

  /**
   * 创建指定表、segment 和提交时间的版本化增量 key。
   *
   * @param tId 表 id 与 epoch
   * @param segmentId rowId 分段编号
   * @param commitTs 提交时间戳
   * @return versioned segment row-count delta key
   */
  public static VersionSegmentRowCountDeltaKey of(TabId tId,
      long segmentId, long commitTs) {
    DynamicByteBuffer b = DynamicByteBuffer.c();
    b.put(KeyType.META.getCode());
    b.put(MetaType.TABLE_SEGMENT_ROW_COUNT_DELTA.getCode());
    b.putInt(tId.id);
    b.putLong(tId.epoch);
    b.putLong(Key.flipSign(segmentId));
    b.putLong(Key.flipSign(commitTs));
    return new VersionSegmentRowCountDeltaKey(b.toArray());
  }
}
