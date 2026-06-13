package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.adb.key.VersionKey;
import net.xdob.vexra.adb.ldb.LdbStore;
import org.h2.value.ValueVarchar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADB lock resolve worker 测试。
 *
 * <p>验证后台 worker 能复用 `AdbLockResolver` 的批量 resolve 能力，并对外记录最近
 * 一轮结果，供后续运维入口暴露。</p>
 */
class AdbLockResolveWorkerTest {
  @TempDir
  private Path tempDir;

  /**
   * 验证手动触发一轮 resolve 会清理过期 lock 并记录结果。
   */
  @Test
  void shouldResolveOnceAndExposeLastResult() throws Exception {
    try (LdbStore store = new LdbStore(
        tempDir.resolve("resolve-once").toString());
         AdbLockResolveWorker worker = new AdbLockResolveWorker(store,
             () -> 7, 0, 1000)) {
      RowKey expired = rowKey(1);
      RowKey waiting = rowKey(2);
      prewrite(store, 20, expired, 1, 5);
      prewrite(store, 21, waiting, 10, 20);

      AdbLockResolveBatchResult result = worker.resolveOnce();

      assertEquals(1, result.getScannedLocks());
      assertEquals(1, result.getRolledBackLocks());
      assertEquals(0, result.getRolledForwardLocks());
      assertTrue(worker.getLastResult().isPresent());
      assertFalse(worker.getLastFailure().isPresent());
      assertNull(store.get(VersionKey.of(expired, false, 20).toBytes()));
      assertNotNull(store.get(VersionKey.of(waiting, false, 21).toBytes()));
    }
  }

  /**
   * 验证 start 后的周期调度会执行 resolve，且重复 start 不会创建额外调度。
   */
  @Test
  void shouldResolveExpiredLocksAfterStart() throws Exception {
    try (LdbStore store = new LdbStore(
        tempDir.resolve("scheduled").toString());
         AdbLockResolveWorker worker = new AdbLockResolveWorker(store,
             () -> 7, 0, 60000)) {
      RowKey expired = rowKey(3);
      prewrite(store, 22, expired, 1, 5);

      worker.start();
      worker.start();

      assertTrue(worker.isStarted());
      waitUntilResolved(worker, store, expired, 22);
      assertEquals(1, worker.getLastResult().get().getRolledBackLocks());
      assertFalse(worker.getLastFailure().isPresent());
    }
  }

  private static void waitUntilResolved(AdbLockResolveWorker worker,
      LdbStore store, RowKey key, long txnId) throws Exception {
    long deadline = System.currentTimeMillis() + 2000;
    while (System.currentTimeMillis() < deadline) {
      if (worker.getLastResult().isPresent()
          && store.get(VersionKey.of(key, false, txnId).toBytes()) == null) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("lock was not resolved by background worker");
  }

  private static void prewrite(LdbStore store, long txnId, RowKey key,
      long startTs, long ttlMillis) throws Exception {
    AdbPrewriteApplicator.prewrite(store, txnId, startTs,
        Collections.singletonList(new AdbRegionMutation(key,
            rowValue(txnId, "lock-worker"))),
        Collections.singletonList(new AdbTxnLock(txnId, key.toBytes(),
            key.toBytes(), startTs, "r1", ttlMillis)));
  }

  private static RowValue rowValue(long txnId, String value) {
    RowValue rowValue = new RowValue();
    rowValue.txnId = txnId;
    rowValue.payload = RowCodec.encode(ValueVarchar.get(value));
    return rowValue;
  }

  private static RowKey rowKey(long rowId) {
    return RowKey.of(TabId.of(1, 0L), rowId);
  }
}
