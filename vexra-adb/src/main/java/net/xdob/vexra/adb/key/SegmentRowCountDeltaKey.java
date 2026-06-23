package net.xdob.vexra.adb.key;

import net.xdob.vexra.adb.db.DynamicByteBuffer;
import net.xdob.vexra.adb.db.KeyType;

import java.nio.ByteBuffer;

/**
 * ADB 表 rowId 分段记录数增量的逻辑 META key。
 *
 * <p>该 key 用于 range count 的可选 segment 统计路径，和表级
 * {@link RowCountDeltaKey} 一样只记录提交事务带来的行数变化。它不替代真实
 * row version 数据，缺失时调用方必须回退到逐行可见性扫描。</p>
 */
public class SegmentRowCountDeltaKey extends Key {
  protected static final int HEADER_SIZE = 22;
  protected final KeyType type;
  protected final MetaType metaType;
  protected final int tableId;
  protected final long epoch;
  protected final long segmentId;

  SegmentRowCountDeltaKey(byte[] data) {
    super(data);
    if (this.data.length < HEADER_SIZE) {
      throw new IllegalArgumentException(
          "Invalid SegmentRowCountDeltaKey bytes, length="
              + this.data.length);
    }

    ByteBuffer wrap = ByteBuffer.wrap(this.data);
    this.type = KeyType.getByCode(wrap.get());
    if (this.type != KeyType.META) {
      throw new IllegalArgumentException("Not a meta key, type="
          + this.type);
    }
    this.metaType = MetaType.getByCode(wrap.get());
    if (this.metaType != MetaType.TABLE_SEGMENT_ROW_COUNT_DELTA) {
      throw new IllegalArgumentException(
          "Not a SegmentRowCountDeltaKey key, metaType=" + this.metaType);
    }

    this.tableId = wrap.getInt();
    this.epoch = wrap.getLong();
    this.segmentId = Key.flipSign(wrap.getLong());
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

  public long getSegmentId() {
    return segmentId;
  }

  public TabId getTabKey() {
    return TabId.of(tableId, epoch);
  }

  public static SegmentRowCountDeltaKey fromBytes(byte[] data) {
    return new SegmentRowCountDeltaKey(data);
  }

  /**
   * 创建指定表和 segment 的逻辑增量 key。
   *
   * @param tId 表 id 与 epoch
   * @param segmentId rowId 分段编号
   * @return segment row-count delta key
   */
  public static SegmentRowCountDeltaKey of(TabId tId, long segmentId) {
    DynamicByteBuffer b = DynamicByteBuffer.c();
    b.put(KeyType.META.getCode());
    b.put(MetaType.TABLE_SEGMENT_ROW_COUNT_DELTA.getCode());
    b.putInt(tId.id);
    b.putLong(tId.epoch);
    b.putLong(Key.flipSign(segmentId));
    return new SegmentRowCountDeltaKey(b.toArray());
  }
}
