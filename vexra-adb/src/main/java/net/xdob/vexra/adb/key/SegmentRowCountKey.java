package net.xdob.vexra.adb.key;

import net.xdob.vexra.adb.db.DynamicByteBuffer;
import net.xdob.vexra.adb.db.KeyType;

import java.nio.ByteBuffer;

/**
 * ADB 表 rowId 分段记录数 base snapshot 的逻辑 META key。
 *
 * <p>该 key 是 segment range count 的读后优化元数据，记录某个 segment
 * 累加到特定提交时间后的可见行数。它与 segment delta key 并存；缺失时读路径从
 * 0 加 delta 计算，保证旧库兼容。</p>
 */
public class SegmentRowCountKey extends Key {
  protected static final int HEADER_SIZE = 22;
  protected final KeyType type;
  protected final MetaType metaType;
  protected final int tableId;
  protected final long epoch;
  protected final long segmentId;

  SegmentRowCountKey(byte[] data) {
    super(data);
    if (this.data.length < HEADER_SIZE) {
      throw new IllegalArgumentException(
          "Invalid SegmentRowCountKey bytes, length=" + this.data.length);
    }

    ByteBuffer wrap = ByteBuffer.wrap(this.data);
    this.type = KeyType.getByCode(wrap.get());
    if (this.type != KeyType.META) {
      throw new IllegalArgumentException("Not a meta key, type="
          + this.type);
    }
    this.metaType = MetaType.getByCode(wrap.get());
    if (this.metaType != MetaType.TABLE_SEGMENT_ROW_COUNT) {
      throw new IllegalArgumentException(
          "Not a SegmentRowCountKey key, metaType=" + this.metaType);
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

  /**
   * 从落盘字节解析 segment row-count base key。
   *
   * @param data META CF 中保存的 key 字节
   * @return segment row-count base key
   */
  public static SegmentRowCountKey fromBytes(byte[] data) {
    return new SegmentRowCountKey(data);
  }

  /**
   * 创建指定表和 segment 的 base snapshot key。
   *
   * @param tId 表 id 与 epoch
   * @param segmentId rowId 分段编号
   * @return segment row-count base key
   */
  public static SegmentRowCountKey of(TabId tId, long segmentId) {
    DynamicByteBuffer b = DynamicByteBuffer.c();
    b.put(KeyType.META.getCode());
    b.put(MetaType.TABLE_SEGMENT_ROW_COUNT.getCode());
    b.putInt(tId.id);
    b.putLong(tId.epoch);
    b.putLong(Key.flipSign(segmentId));
    return new SegmentRowCountKey(b.toArray());
  }
}
