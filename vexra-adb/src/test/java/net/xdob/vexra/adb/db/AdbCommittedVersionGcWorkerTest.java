package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.adb.key.VersionKey;
import net.xdob.vexra.adb.ldb.LdbStore;
import org.h2.value.ValueVarchar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADB committed version GC worker 测试。
 *
 * <p>验证后台 worker 能复用 `AdbCommittedVersionGcCleaner` 的历史版本删除语义，
 * 并对外记录最近一轮结果或失败，供后续运维入口暴露。</p>
 */
class AdbCommittedVersionGcWorkerTest {
  @TempDir
  private Path tempDir;

  /**
   * 验证手动触发一轮 GC 会删除 safe point 前的旧版本并记录结果。
   */
  @Test
  void shouldCleanOnceAndExposeLastResult() throws Exception {
    try (LdbStore store = new LdbStore(
        tempDir.resolve("gc-once").toString());
         AdbCommittedVersionGcWorker worker =
             new AdbCommittedVersionGcWorker(store,
                 new AdbGcSafePointManager(25), 0, 1000)) {
      RowKey row = rowKey(1);
      putCommitted(store, row, 30);
      putCommitted(store, row, 20);
      putCommitted(store, row, 10);

      AdbGcCleanResult result = worker.cleanOnce();

      assertEquals(3, result.getScannedVersions());
      assertEquals(2, result.getDeletedVersions());
      assertTrue(worker.getLastResult().isPresent());
      assertFalse(worker.getLastFailure().isPresent());
      assertNotNull(store.get(VersionKey.of(row, true, 30).toBytes()));
      assertNull(store.get(VersionKey.of(row, true, 20).toBytes()));
      assertNull(store.get(VersionKey.of(row, true, 10).toBytes()));
    }
  }

  /**
   * 验证 start 后的周期调度会执行 GC，重复 start 不会创建额外调度。
   */
  @Test
  void shouldCleanCommittedVersionsAfterStart() throws Exception {
    try (LdbStore store = new LdbStore(
        tempDir.resolve("gc-scheduled").toString());
         AdbCommittedVersionGcWorker worker =
             new AdbCommittedVersionGcWorker(store,
                 new AdbGcSafePointManager(35), 1, 60000)) {
      RowKey row = rowKey(2);
      putCommitted(store, row, 40);
      putCommitted(store, row, 30);

      worker.start();
      worker.start();

      assertTrue(worker.isStarted());
      waitUntilCleaned(worker, store, row);
      assertEquals(1, worker.getLastResult().get().getDeletedVersions());
      assertFalse(worker.getLastFailure().isPresent());
    }
  }

  /**
   * 验证 GC 失败会记录最近一次异常，便于后续诊断入口读取。
   */
  @Test
  void shouldRecordLastFailure() throws Exception {
    try (LdbStore store = new LdbStore(
        tempDir.resolve("gc-failure").toString());
         AdbCommittedVersionGcWorker worker =
             new AdbCommittedVersionGcWorker(store,
                 new AdbGcSafePointManager(10), 0, 60000)) {
      store.put(new byte[] {1, 2, 3}, new byte[] {4});

      assertThrows(SQLException.class, worker::cleanOnce);

      assertTrue(worker.getLastFailure().isPresent());
      assertFalse(worker.getLastResult().isPresent());
    }
  }

  private static void waitUntilCleaned(AdbCommittedVersionGcWorker worker,
      LdbStore store, RowKey key) throws Exception {
    long deadline = System.currentTimeMillis() + 2000;
    while (System.currentTimeMillis() < deadline) {
      if (worker.getLastResult().isPresent()
          && store.get(VersionKey.of(key, true, 30).toBytes()) == null) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("committed version was not cleaned by worker");
  }

  private static void putCommitted(LdbStore store, RowKey key, long commitTs)
      throws Exception {
    store.put(VersionKey.of(key, true, commitTs).toBytes(),
        RowValue.encodeValue(rowValue(commitTs, commitTs,
            "committed-" + commitTs)));
  }

  private static RowValue rowValue(long txnId, long commitTs, String value) {
    RowValue rowValue = new RowValue();
    rowValue.txnId = txnId;
    rowValue.commitTs = commitTs;
    rowValue.payload = RowCodec.encode(ValueVarchar.get(value));
    return rowValue;
  }

  private static RowKey rowKey(long rowId) {
    return RowKey.of(TabId.of(1, 0L), rowId);
  }
}
