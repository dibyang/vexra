package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.adb.key.TableNextRowIdKey;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicLong;

public class KeyGenerator {

  private static final int STEP = 1000;

  private final AtomicLong next = new AtomicLong(0);
  /**
   * 鏈湴宸茬敵璇峰彿娈电殑缁撴潫浣嶇疆锛堝紑鍖洪棿锛?
   * 鍙垎閰嶅尯闂翠负 [next, maxExclusive)
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

    // 鍏ㄥ眬璁℃暟鍣ㄦ帹杩?STEP
    dbStore.addLong(CF.META.getCfId(), key, STEP);

    // 璇诲嚭鎺ㄨ繘鍚庣殑鏂颁笂鐣岋細琛ㄧず鈥滃凡鍒嗛厤鍒扮殑鏈€澶?rowId鈥?
    long newMax = dbStore.getLong(CF.META.getCfId(), key).orElse(0L);
    if (newMax < STEP) {
      throw new IllegalStateException("invalid rowId counter value: " + newMax);
    }

    long start = newMax - STEP + 1;   // 鍖呭惈
    long endExclusive = newMax + 1;   // 涓嶅寘鍚?

    next.set(start);
    maxExclusive = endExclusive;
  }
}
