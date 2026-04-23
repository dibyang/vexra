package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.adb.key.TxnNextIdKey;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicLong;

public class TxnIdGenerator {

  private final AtomicLong nextTxnId = new AtomicLong(0);
  private volatile long maxTxnIdExclusive = 0;

  private static final int STEP = 1000;

  private final DbStore dbStore;
  private final byte[] key = TxnNextIdKey.of().toBytes();

  public TxnIdGenerator(DbStore dbStore) {
    this.dbStore = dbStore;
  }

  public long nextTxnId() {
    long id = nextTxnId.getAndIncrement();
    if (id < maxTxnIdExclusive) {
      return id;
    }
    return slowPath();
  }

  private synchronized long slowPath() {
    try {
      long id = nextTxnId.getAndIncrement();
      if (id < maxTxnIdExclusive) {
        return id;
      }

      allocateSegment();

      id = nextTxnId.getAndIncrement();
      if (id < maxTxnIdExclusive) {
        return id;
      }
      throw new IllegalStateException("failed to allocate txnId segment");
    } catch (SQLException e) {
      throw new IllegalStateException("allocate txnId segment error", e);
    }
  }

  private void allocateSegment() throws SQLException {

    // 全局计数器推进 STEP
    dbStore.addLong(CF.META.getCfId(), key, STEP);

    // 读出推进后的新上界：表示“已分配到的最大 rowId”
    long newMax = dbStore.getLong(CF.META.getCfId(), key).orElse(0L);
    if (newMax < STEP) {
      throw new IllegalStateException("invalid txnId counter value: " + newMax);
    }

    long start = newMax - STEP + 1;   // 包含
    long endExclusive = newMax + 1;   // 不包含

    nextTxnId.set(start);
    maxTxnIdExclusive = endExclusive;
  }

}
