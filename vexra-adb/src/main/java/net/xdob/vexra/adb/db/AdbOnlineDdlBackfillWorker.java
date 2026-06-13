package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.DataKey;
import net.xdob.vexra.adb.key.IndexKey;
import net.xdob.vexra.adb.key.IndexPrefix;
import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.RowPrefix;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.cluster.ddl.DdlJob;
import net.xdob.vexra.cluster.ddl.DdlJobState;
import org.h2.result.Row;
import org.h2.table.IndexColumn;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * ADB Online DDL 索引回填 worker。
 *
 * <p>worker 负责把 ADD_INDEX job 的 BACKFILLING 状态接到真实 ADB KV：
 * 按主表 row key 顺序扫描可见行，使用与 {@link AdbSecondaryIndex} 相同的编码规则生成
 * 二级索引 key，并通过 {@link TxnManager#addIndexBatch(Transaction2, IndexPrefix,
 * java.util.Collection)} 批量写入。该类不拥有后台线程和分布式任务租约，调用方负责调度、
 * 持久化 job 快照和失败重试。</p>
 */
public final class AdbOnlineDdlBackfillWorker {
  private final TxnManager txnManager;
  private final AdbOnlineDdlRuntimeController controller;
  private final int batchSize;

  /**
   * 创建 Online DDL backfill worker。
   *
   * @param txnManager ADB 事务管理器
   * @param controller Online DDL 状态控制器
   * @param batchSize 单批最多回填行数
   */
  public AdbOnlineDdlBackfillWorker(TxnManager txnManager,
      AdbOnlineDdlRuntimeController controller, int batchSize) {
    this.txnManager = Objects.requireNonNull(txnManager,
        "txnManager == null");
    this.controller = Objects.requireNonNull(controller,
        "controller == null");
    if (batchSize <= 0) {
      throw new IllegalArgumentException("batchSize must be positive");
    }
    this.batchSize = batchSize;
  }

  /**
   * 执行一批索引回填。
   *
   * @param job 当前 BACKFILLING job
   * @param tabId 表标识
   * @param indexId 目标索引 ID
   * @param indexColumns 目标索引列
   * @return 单批执行结果，包含新的 job 断点和是否扫描完成
   * @throws SQLException 当主表扫描、索引编码或索引写入失败时抛出
   */
  public AdbOnlineDdlBackfillResult runBatch(DdlJob job, TabId tabId,
      int indexId, IndexColumn[] indexColumns) throws SQLException {
    requireBackfilling(job);
    Objects.requireNonNull(tabId, "tabId == null");
    Objects.requireNonNull(indexColumns, "indexColumns == null");

    Long minRowId = nextRowId(job);
    if (minRowId == null && hasMaxRowCheckpoint(job)) {
      return new AdbOnlineDdlBackfillResult(job, 0, true);
    }

    Transaction2 txn = txnManager.beginTransaction();
    List<IndexKey> indexKeys = new ArrayList<>();
    byte[] lastCompletedKey = null;

    try (TableScanCursor cursor = txnManager.entryIterator(txn,
        RowPrefix.of(tabId), minRowId, null)) {
      while (indexKeys.size() < batchSize && cursor.next()) {
        RowValue rowValue = cursor.get();
        Row row = RowCodec.decode(rowValue.rowKey, rowValue.payload);
        byte[] encodedIndex = encodeIndex(row, indexColumns);
        indexKeys.add(IndexKey.of(tabId, indexId, encodedIndex,
            row.getKey()));
        lastCompletedKey = RowKey.of(tabId, row.getKey()).toBytes();
      }
    } finally {
      txnManager.releaseInternalTransaction(txn);
    }

    DdlJob progressed = job;
    if (!indexKeys.isEmpty()) {
      txnManager.addIndexBatch(txn, IndexPrefix.of(tabId, indexId), indexKeys);
      progressed = controller.advanceBackfill(job, lastCompletedKey,
          job.getBackfillProgress().getCompletedRows() + indexKeys.size());
    }

    boolean completed = indexKeys.size() < batchSize;
    return new AdbOnlineDdlBackfillResult(progressed, indexKeys.size(),
        completed);
  }

  /**
   * 持续执行 backfill，直到主表扫描完成并发布索引。
   *
   * @param job 当前 BACKFILLING job
   * @param tabId 表标识
   * @param indexId 目标索引 ID
   * @param indexColumns 目标索引列
   * @return 发布后的执行结果，job 状态为 PUBLIC
   * @throws SQLException 当回填或发布失败时抛出
   */
  public AdbOnlineDdlBackfillResult runToCompletion(DdlJob job, TabId tabId,
      int indexId, IndexColumn[] indexColumns) throws SQLException {
    DdlJob current = job;
    long lastBatchRows = 0;
    while (true) {
      AdbOnlineDdlBackfillResult result = runBatch(current, tabId, indexId,
          indexColumns);
      current = result.getJob();
      lastBatchRows = result.getBatchRows();
      if (result.isCompleted()) {
        DdlJob published = controller.publishAddIndex(current, tabId, indexId);
        return new AdbOnlineDdlBackfillResult(published, lastBatchRows, true);
      }
    }
  }

  /**
   * 将失败的 backfill job 标记为 FAILED。
   *
   * @param job 当前 job
   * @return FAILED 状态的 job
   */
  public DdlJob markFailed(DdlJob job) {
    return controller.fail(job);
  }

  private byte[] encodeIndex(Row row, IndexColumn[] indexColumns)
      throws SQLException {
    try {
      return SearchRowCodec.encode(row, indexColumns, false);
    } catch (RuntimeException e) {
      throw new SQLException("Failed to encode index backfill row "
          + row.getKey(), e);
    }
  }

  private static void requireBackfilling(DdlJob job) {
    Objects.requireNonNull(job, "job == null");
    if (job.getState() != DdlJobState.BACKFILLING) {
      throw new IllegalStateException(
          "backfill worker requires BACKFILLING state");
    }
  }

  private static Long nextRowId(DdlJob job) throws SQLException {
    byte[] checkpoint = job.getBackfillProgress().getLastCompletedKey();
    if (checkpoint == null || checkpoint.length == 0) {
      return null;
    }
    DataKey key;
    try {
      key = DataKey.fromBytes(checkpoint);
    } catch (RuntimeException e) {
      throw new SQLException("Invalid backfill checkpoint key", e);
    }
    if (!key.isRow()) {
      throw new SQLException("Backfill checkpoint is not a row key");
    }
    long rowId = key.getRowId();
    if (rowId == Long.MAX_VALUE) {
      return null;
    }
    return rowId + 1;
  }

  private static boolean hasMaxRowCheckpoint(DdlJob job) throws SQLException {
    byte[] checkpoint = job.getBackfillProgress().getLastCompletedKey();
    if (checkpoint == null || checkpoint.length == 0) {
      return false;
    }
    try {
      DataKey key = DataKey.fromBytes(checkpoint);
      return key.isRow() && key.getRowId() == Long.MAX_VALUE;
    } catch (RuntimeException e) {
      throw new SQLException("Invalid backfill checkpoint key", e);
    }
  }
}
