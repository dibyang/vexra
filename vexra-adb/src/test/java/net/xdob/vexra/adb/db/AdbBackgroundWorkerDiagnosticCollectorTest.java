package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.adb.ldb.LdbStore;
import org.h2.value.ValueVarchar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADB 后台 worker 诊断采集器测试。
 *
 * <p>测试使用真实 worker 执行结果，证明 lock resolve / GC 最近结果和失败可以进入
 * 诊断包字段，同时采集动作本身不触发新的后台任务。</p>
 */
class AdbBackgroundWorkerDiagnosticCollectorTest {
  @TempDir
  private Path tempDir;

  /**
   * 验证 lock resolve 与 GC 的最近成功结果可转换成诊断字段。
   */
  @Test
  void shouldCollectLastWorkerResults() throws Exception {
    try (LdbStore store = new LdbStore(tempDir.resolve("workers").toString());
         AdbLockResolveWorker lockWorker = new AdbLockResolveWorker(store,
             () -> 10, 0, 1000);
         AdbCommittedVersionGcWorker gcWorker =
             new AdbCommittedVersionGcWorker(store,
                 new AdbGcSafePointManager(25), 0, 1000)) {
      RowKey expired = rowKey(1);
      AdbPrewriteApplicator.prewrite(store, 20, 1,
          Collections.singletonList(new AdbRegionMutation(expired,
              rowValue(20, "lock"))),
          Collections.singletonList(new AdbTxnLock(20, expired.toBytes(),
              expired.toBytes(), 1, "r1", 5)));
      RowKey row = rowKey(2);
      store.put(net.xdob.vexra.adb.key.VersionKey.of(row, true, 30).toBytes(),
          RowValue.encodeValue(rowValue(30, "new")));
      store.put(net.xdob.vexra.adb.key.VersionKey.of(row, true, 10).toBytes(),
          RowValue.encodeValue(rowValue(10, "old")));

      lockWorker.resolveOnce();
      gcWorker.cleanOnce();

      AdbBackgroundWorkerDiagnosticCollector.Snapshot snapshot =
          new AdbBackgroundWorkerDiagnosticCollector(lockWorker,
              gcWorker).collect();

      assertEquals("true",
          snapshot.getOperations().get("worker.lockResolve.present"));
      assertEquals("1",
          snapshot.getOperations().get("worker.lockResolve.scannedLocks"));
      assertEquals("1",
          snapshot.getOperations().get("worker.gc.deletedVersions"));
      assertEquals(1,
          snapshot.getMetrics().get("adb_worker_lock_resolve_scanned_locks"));
      assertEquals(1,
          snapshot.getMetrics().get("adb_worker_gc_deleted_versions"));
    }
  }

  /**
   * 验证 worker 缺失和失败状态会被清晰记录。
   */
  @Test
  void shouldCollectMissingAndFailureState() throws Exception {
    try (LdbStore store = new LdbStore(tempDir.resolve("failure").toString());
         AdbCommittedVersionGcWorker gcWorker =
             new AdbCommittedVersionGcWorker(store,
                 new AdbGcSafePointManager(10), 0, 1000)) {
      store.put(new byte[] {1, 2, 3}, new byte[] {4});
      try {
        gcWorker.cleanOnce();
      } catch (java.sql.SQLException expected) {
        // 诊断采集需要保留最近失败，测试继续读取 worker 状态。
      }

      AdbBackgroundWorkerDiagnosticCollector.Snapshot snapshot =
          new AdbBackgroundWorkerDiagnosticCollector(null, gcWorker).collect();

      assertEquals("false",
          snapshot.getOperations().get("worker.lockResolve.present"));
      assertEquals("true",
          snapshot.getOperations().get("worker.gc.lastFailurePresent"));
      assertTrue(snapshot.getOperations()
          .get("worker.gc.lastFailureMessage").contains("Failed"));
      assertEquals(1,
          snapshot.getMetrics().get("adb_worker_gc_last_failure_present"));
    }
  }

  private static RowKey rowKey(long rowId) {
    return RowKey.of(TabId.of(1, 0L), rowId);
  }

  private static RowValue rowValue(long txnId, String value) {
    RowValue rowValue = new RowValue();
    rowValue.txnId = txnId;
    rowValue.commitTs = txnId;
    rowValue.payload = RowCodec.encode(ValueVarchar.get(value));
    return rowValue;
  }
}
