package net.xdob.vexra.adb.key;

import net.xdob.vexra.adb.db.DynamicByteBuffer;
import net.xdob.vexra.adb.db.KeyType;

import java.nio.ByteBuffer;

/**
 * 带提交时间戳的 segment row-count base snapshot key。
 *
 * <p>commitTs 采用与表级 {@link VersionRowCountKey} 相同的倒序编码，
 * 使较新的 base snapshot 排在前面。读旧快照时会跳过晚于 startTs 的 base，
 * 再叠加后续 segment delta。</p>
 */
public class VersionSegmentRowCountKey extends SegmentRowCountKey {
  protected static final int HEADER_SIZE = 30;
  private final long commitTs;

  VersionSegmentRowCountKey(byte[] data) {
    super(data);
    if (this.data.length != HEADER_SIZE) {
      throw new IllegalArgumentException(
          "Invalid VersionSegmentRowCountKey bytes, length="
              + this.data.length);
    }
    ByteBuffer wrap = ByteBuffer.wrap(this.data);
    this.commitTs = Long.MAX_VALUE - Key.flipSign(
        wrap.getLong(HEADER_SIZE - Long.BYTES));
  }

  public long getCommitTs() {
    return commitTs;
  }

  /**
   * 从落盘字节解析带提交时间戳的 segment row-count base key。
   *
   * @param data META CF 中保存的 key 字节
   * @return versioned segment row-count base key
   */
  public static VersionSegmentRowCountKey fromBytes(byte[] data) {
    return new VersionSegmentRowCountKey(data);
  }

  /**
   * 创建指定表、segment 和提交时间的 base snapshot key。
   *
   * @param tId 表 id 与 epoch
   * @param segmentId rowId 分段编号
   * @param commitTs base snapshot 覆盖到的提交时间戳
   * @return versioned segment row-count base key
   */
  public static VersionSegmentRowCountKey of(TabId tId, long segmentId,
      long commitTs) {
    DynamicByteBuffer b = DynamicByteBuffer.c();
    b.put(KeyType.META.getCode());
    b.put(MetaType.TABLE_SEGMENT_ROW_COUNT.getCode());
    b.putInt(tId.id);
    b.putLong(tId.epoch);
    b.putLong(Key.flipSign(segmentId));
    b.putLong(Key.flipSign(Long.MAX_VALUE - commitTs));
    return new VersionSegmentRowCountKey(b.toArray());
  }
}
