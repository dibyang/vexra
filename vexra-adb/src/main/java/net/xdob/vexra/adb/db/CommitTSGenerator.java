package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.adb.key.CommitTSKey;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicLong;

public class CommitTSGenerator {

  private final AtomicLong nextCommitTs = new AtomicLong(0);
  private volatile long maxCommitTsExclusive = 0;

  private static final int STEP = 1000;

  private final DbStore dbStore;
  private final byte[] key = CommitTSKey.of().toBytes();

  public CommitTSGenerator(DbStore dbStore) {
    this.dbStore = dbStore;
  }

  public long nextCommitTs() {
    long id = nextCommitTs.getAndIncrement();
    if (id < maxCommitTsExclusive) {
      return id;
    }
    return slowPath(true);
  }

  public long lastCommitTs() {
    long id = nextCommitTs.get();
    if (id < maxCommitTsExclusive) {
      return id;
    }
    return slowPath(false);
  }


  private synchronized long slowPath(boolean next) {
    try {
      long id = next?nextCommitTs.getAndIncrement():nextCommitTs.get();
      if (id < maxCommitTsExclusive) {
        return id;
      }

      allocateSegment();

      id = next?nextCommitTs.getAndIncrement():nextCommitTs.get();
      if (id < maxCommitTsExclusive) {
        return id;
      }
      throw new IllegalStateException("failed to allocate commitTS segment");
    } catch (SQLException e) {
      throw new IllegalStateException("allocate commitTS segment error", e);
    }
  }

  private void allocateSegment() throws SQLException {

    // 全局计数器推进 STEP
    dbStore.addLong(CF.META.getCfId(), key, STEP);

    // 读出推进后的新上界：表示“已分配到的最大 rowId”
    long newMax = dbStore.getLong(CF.META.getCfId(), key).orElse(0L);
    if (newMax < STEP) {
      throw new IllegalStateException("invalid commitTS counter value: " + newMax);
    }

    long start = newMax - STEP + 1;   // 包含
    long endExclusive = newMax + 1;   // 不包含

    nextCommitTs.set(start);
    maxCommitTsExclusive = endExclusive;
  }

}
