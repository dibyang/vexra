package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.adb.key.TableNextRowIdKey;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicLong;

public class KeyGenerator {

  private static final int STEP = 1000;

  private final AtomicLong next = new AtomicLong(0);
  /**
   * 本地已申请号段的结束位置（开区间）
   * 可分配区间为 [next, maxExclusive)
   */
  private volatile long maxExclusive = 0;

  private final DbStore dbStore;
  private final TableNextRowIdKey tableNextRowIdKey;

  public KeyGenerator(DbStore dbStore, int tableId) {
    this.dbStore = dbStore;
    this.tableNextRowIdKey = TableNextRowIdKey.of(tableId);
  }

  public long nextKey() throws SQLException {
    long id = next.getAndIncrement();
    if (id < maxExclusive) {
      return id;
    }
    return slowPath();
  }

  private synchronized long slowPath() throws SQLException {
    long id = next.getAndIncrement();
    if (id < maxExclusive) {
      return id;
    }

    allocateSegment();

    id = next.getAndIncrement();
    if (id < maxExclusive) {
      return id;
    }

    throw new IllegalStateException("failed to allocate key segment");
  }

  private void allocateSegment() throws SQLException {
    byte[] key = tableNextRowIdKey.toBytes();

    // 全局计数器推进 STEP
    dbStore.addLong(CF.META.getCfId(), key, STEP);

    // 读出推进后的新上界：表示“已分配到的最大 rowId”
    long newMax = dbStore.getLong(CF.META.getCfId(), key).orElse(0L);
    if (newMax < STEP) {
      throw new IllegalStateException("invalid rowId counter value: " + newMax);
    }

    long start = newMax - STEP + 1;   // 包含
    long endExclusive = newMax + 1;   // 不包含

    next.set(start);
    maxExclusive = endExclusive;
  }
}
