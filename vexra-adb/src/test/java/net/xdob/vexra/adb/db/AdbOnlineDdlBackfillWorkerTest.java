package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.IndexBuildState;
import net.xdob.vexra.adb.key.IndexKey;
import net.xdob.vexra.adb.key.IndexPrefix;
import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.adb.ldb.LdbStore;
import net.xdob.vexra.cluster.ddl.DdlJob;
import net.xdob.vexra.cluster.ddl.DdlJobState;
import net.xdob.vexra.cluster.ddl.SchemaVersion;
import org.h2.result.DefaultRow;
import org.h2.result.Row;
import org.h2.table.Column;
import org.h2.table.IndexColumn;
import org.h2.value.TypeInfo;
import org.h2.value.Value;
import org.h2.value.ValueBigint;
import org.h2.value.ValueVarchar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADB Online DDL backfill worker 测试。
 *
 * <p>测试覆盖 ADB-Prod-04 的核心验收：真实主表扫描、二级索引 KV 回填、断点续跑、
 * 最终 READY 发布，以及回填异常后的失败标记。</p>
 */
class AdbOnlineDdlBackfillWorkerTest {
  @TempDir
  File tempDir;

  /**
   * 验证 backfill 可以分批处理、基于断点续跑，并在完成后发布 READY。
   */
  @Test
  void shouldBackfillIndexInBatchesAndPublishReady() throws Exception {
    TabId tabId = TabId.of(10, 0L);
    int indexId = 3;
    try (LdbStore store = new LdbStore(new File(tempDir, "backfill-store")
        .getAbsolutePath())) {
      TxnManager manager = new TxnManager(store);
      seedRows(manager, tabId);
      AdbOnlineDdlRuntimeController controller =
          new AdbOnlineDdlRuntimeController(manager);
      AdbOnlineDdlBackfillWorker worker =
          new AdbOnlineDdlBackfillWorker(manager, controller, 2);

      DdlJob backfilling = controller.beginBackfill(
          controller.startAddIndex("job-backfill", tabId, indexId,
              new SchemaVersion(1)));

      AdbOnlineDdlBackfillResult first = worker.runBatch(backfilling, tabId,
          indexId, nameIndexColumns());
      AdbOnlineDdlBackfillResult finished = worker.runToCompletion(
          first.getJob(), tabId, indexId, nameIndexColumns());

      assertFalse(first.isCompleted());
      assertEquals(2, first.getBatchRows());
      assertEquals(2,
          first.getJob().getBackfillProgress().getCompletedRows());
      assertArrayEquals(RowKey.of(tabId, 2).toBytes(),
          first.getJob().getBackfillProgress().getLastCompletedKey());
      assertEquals(DdlJobState.PUBLIC, finished.getJob().getState());
      assertEquals(IndexBuildState.READY,
          manager.getIndexBuildState(tabId, indexId));
      assertEquals(Arrays.asList(1L, 2L, 3L),
          scanIndexRowIds(manager, tabId, indexId));
    }
  }

  /**
   * 验证基于已有 checkpoint 恢复时不会重复回填已经完成的 row。
   */
  @Test
  void shouldResumeFromLastCompletedRowKey() throws Exception {
    TabId tabId = TabId.of(11, 0L);
    int indexId = 4;
    try (LdbStore store = new LdbStore(new File(tempDir, "resume-store")
        .getAbsolutePath())) {
      TxnManager manager = new TxnManager(store);
      seedRows(manager, tabId);
      AdbOnlineDdlRuntimeController controller =
          new AdbOnlineDdlRuntimeController(manager);

      DdlJob backfilling = controller.beginBackfill(
          controller.startAddIndex("job-resume", tabId, indexId,
              new SchemaVersion(7)));
      AdbOnlineDdlBackfillWorker firstWorker =
          new AdbOnlineDdlBackfillWorker(manager, controller, 1);
      DdlJob checkpoint = firstWorker.runBatch(backfilling, tabId, indexId,
          nameIndexColumns()).getJob();

      AdbOnlineDdlBackfillWorker resumedWorker =
          new AdbOnlineDdlBackfillWorker(manager, controller, 5);
      AdbOnlineDdlBackfillResult finished = resumedWorker.runToCompletion(
          checkpoint, tabId, indexId, nameIndexColumns());

      assertEquals(3,
          finished.getJob().getBackfillProgress().getCompletedRows());
      assertArrayEquals(RowKey.of(tabId, 3).toBytes(),
          finished.getJob().getBackfillProgress().getLastCompletedKey());
      assertEquals(Arrays.asList(1L, 2L, 3L),
          scanIndexRowIds(manager, tabId, indexId));
    }
  }

  /**
   * 验证回填编码失败会抛出 SQLException，调用方可把 job 标记为 FAILED。
   */
  @Test
  void shouldMarkJobFailedWhenBackfillCannotEncodeIndex() throws Exception {
    TabId tabId = TabId.of(12, 0L);
    int indexId = 5;
    try (LdbStore store = new LdbStore(new File(tempDir, "fail-store")
        .getAbsolutePath())) {
      TxnManager manager = new TxnManager(store);
      seedRows(manager, tabId);
      AdbOnlineDdlRuntimeController controller =
          new AdbOnlineDdlRuntimeController(manager);
      AdbOnlineDdlBackfillWorker worker =
          new AdbOnlineDdlBackfillWorker(manager, controller, 2);
      DdlJob backfilling = controller.beginBackfill(
          controller.startAddIndex("job-fail", tabId, indexId,
              new SchemaVersion(1)));

      assertThrows(SQLException.class, () -> worker.runBatch(backfilling,
          tabId, indexId, invalidIndexColumns()));
      DdlJob failed = worker.markFailed(backfilling);

      assertEquals(DdlJobState.FAILED, failed.getState());
      assertEquals(IndexBuildState.BUILDING,
          manager.getIndexBuildState(tabId, indexId));
    }
  }

  private static void seedRows(TxnManager manager, TabId tabId)
      throws SQLException {
    Transaction2 txn = manager.beginTransaction();
    manager.put(txn, RowKey.of(tabId, 1), rowValue(1, "a"));
    manager.put(txn, RowKey.of(tabId, 2), rowValue(2, "b"));
    manager.put(txn, RowKey.of(tabId, 3), rowValue(3, "c"));
    manager.commit(txn);
  }

  private static RowValue rowValue(long id, String name) {
    RowValue rowValue = new RowValue();
    rowValue.payload = RowCodec.encode(row(id, name));
    return rowValue;
  }

  private static Row row(long id, String name) {
    DefaultRow row = new DefaultRow(new Value[]{
        ValueBigint.get(id), ValueVarchar.get(name)});
    row.setKey(id);
    return row;
  }

  private static IndexColumn[] nameIndexColumns() {
    return IndexColumn.wrap(new Column[]{
        new Column("NAME", TypeInfo.TYPE_VARCHAR, null, 1)});
  }

  private static IndexColumn[] invalidIndexColumns() {
    return IndexColumn.wrap(new Column[]{
        new Column("MISSING", TypeInfo.TYPE_VARCHAR, null, 9)});
  }

  private static List<Long> scanIndexRowIds(TxnManager manager, TabId tabId,
      int indexId) {
    Transaction2 txn = manager.beginTransaction();
    List<Long> rowIds = new ArrayList<>();
    try (IndexScanCursor cursor = manager.indexScanIterator(txn,
        IndexPrefix.of(tabId, indexId), null, null)) {
      while (cursor.next()) {
        IndexKey key = cursor.get();
        rowIds.add(key.getRowId());
      }
    } finally {
      manager.releaseInternalTransaction(txn);
    }
    return rowIds;
  }
}
