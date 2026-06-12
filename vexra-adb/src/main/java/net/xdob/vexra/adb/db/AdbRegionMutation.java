package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.DataKey;

import java.util.Objects;

/**
 * ADB region prewrite mutation。
 *
 * <p>该对象位于 region 2PC PREWRITE 阶段，携带一个 logical data key 及其待写入的
 * MVCC row value。它只描述当前 region 内的 mutation，不负责路由、Raft 复制或
 * commit/rollback。</p>
 */
public final class AdbRegionMutation {
  private final DataKey key;
  private final RowValue value;

  /**
   * 创建 region mutation。
   *
   * @param key logical data key
   * @param value 待预写的行值，调用方传入对象会被复制
   */
  public AdbRegionMutation(DataKey key, RowValue value) {
    this.key = Objects.requireNonNull(key, "key == null");
    this.value = copyValue(Objects.requireNonNull(value, "value == null"));
  }

  public DataKey getKey() {
    return key;
  }

  public RowValue getValue() {
    return copyValue(value);
  }

  private static RowValue copyValue(RowValue source) {
    RowValue copy = new RowValue();
    copy.txnId = source.txnId;
    copy.commitTs = source.commitTs;
    copy.deleted = source.deleted;
    copy.payload = source.payload == null ? null : source.payload.clone();
    copy.rowKey = source.rowKey;
    return copy;
  }
}
