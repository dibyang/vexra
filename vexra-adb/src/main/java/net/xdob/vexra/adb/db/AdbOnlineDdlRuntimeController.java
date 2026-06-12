package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.IndexBuildState;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.cluster.ddl.DdlJob;
import net.xdob.vexra.cluster.ddl.DdlJobState;
import net.xdob.vexra.cluster.ddl.DdlJobStateMachine;
import net.xdob.vexra.cluster.ddl.IndexBackfillProgress;
import net.xdob.vexra.cluster.ddl.SchemaVersion;

import java.sql.SQLException;
import java.util.Objects;

/**
 * ADB Online DDL 运行时控制器。
 *
 * <p>该控制器把公共 DDL job 状态机接入 ADB 的索引可见性状态。当前阶段只负责
 * ADD_INDEX 的 schema version 推进、BUILDING/READY 状态切换和 backfill 断点记录；
 * 真正的索引 KV 回填扫描器由后续 worker 接入。</p>
 */
public final class AdbOnlineDdlRuntimeController {
  public static final String DDL_ADD_INDEX = "ADD_INDEX";

  private final TxnManager txnManager;
  private final DdlJobStateMachine stateMachine;

  /**
   * 创建 Online DDL 运行时控制器。
   *
   * @param txnManager ADB 事务管理器
   */
  public AdbOnlineDdlRuntimeController(TxnManager txnManager) {
    this(txnManager, new DdlJobStateMachine());
  }

  /**
   * 创建 Online DDL 运行时控制器。
   *
   * @param txnManager ADB 事务管理器
   * @param stateMachine DDL 状态机
   */
  public AdbOnlineDdlRuntimeController(TxnManager txnManager,
      DdlJobStateMachine stateMachine) {
    this.txnManager = Objects.requireNonNull(txnManager,
        "txnManager == null");
    this.stateMachine = Objects.requireNonNull(stateMachine,
        "stateMachine == null");
  }

  /**
   * 创建并启动 ADD_INDEX job。
   *
   * @param jobId job 标识
   * @param tabId 表标识
   * @param indexId 索引标识
   * @param baseVersion 当前 schema version
   * @return RUNNING 状态的 DDL job
   * @throws SQLException 当索引状态写入失败时抛出
   */
  public DdlJob startAddIndex(String jobId, TabId tabId, int indexId,
      SchemaVersion baseVersion) throws SQLException {
    DdlJob pending = DdlJob.pending(jobId, DDL_ADD_INDEX,
        Objects.requireNonNull(baseVersion, "baseVersion == null"));
    DdlJob running = stateMachine.transition(pending, DdlJobState.RUNNING);
    txnManager.setIndexBuildState(Objects.requireNonNull(tabId,
        "tabId == null"), indexId, IndexBuildState.BUILDING);
    return running;
  }

  /**
   * 将 ADD_INDEX job 推进到 BACKFILLING。
   *
   * @param job 当前 RUNNING job
   * @return BACKFILLING 状态的 DDL job
   */
  public DdlJob beginBackfill(DdlJob job) {
    requireAddIndex(job);
    return stateMachine.transition(job, DdlJobState.BACKFILLING);
  }

  /**
   * 推进 backfill 断点。
   *
   * @param job 当前 BACKFILLING job
   * @param lastCompletedKey 最后完成 key
   * @param completedRows 已完成行数
   * @return 带新进度的 DDL job
   */
  public DdlJob advanceBackfill(DdlJob job, byte[] lastCompletedKey,
      long completedRows) {
    requireAddIndex(job);
    if (job.getState() != DdlJobState.BACKFILLING) {
      throw new IllegalStateException(
          "backfill progress requires BACKFILLING state");
    }
    if (completedRows < job.getBackfillProgress().getCompletedRows()) {
      throw new IllegalArgumentException("completedRows regression");
    }
    return job.withBackfillProgress(new IndexBackfillProgress(
        lastCompletedKey, completedRows));
  }

  /**
   * 发布 ADD_INDEX job，使索引对新事务可见。
   *
   * @param job 当前 BACKFILLING job
   * @param tabId 表标识
   * @param indexId 索引标识
   * @return PUBLIC 状态的 DDL job
   * @throws SQLException 当索引状态写入失败时抛出
   */
  public DdlJob publishAddIndex(DdlJob job, TabId tabId, int indexId)
      throws SQLException {
    requireAddIndex(job);
    DdlJob published = stateMachine.transition(job, DdlJobState.PUBLIC);
    txnManager.setIndexBuildState(Objects.requireNonNull(tabId,
        "tabId == null"), indexId, IndexBuildState.READY);
    return published;
  }

  /**
   * 将未完成 DDL job 标记为失败。
   *
   * @param job 当前 job
   * @return FAILED 状态的 DDL job
   */
  public DdlJob fail(DdlJob job) {
    requireAddIndex(job);
    if (job.getState() == DdlJobState.ROLLBACK) {
      return stateMachine.transition(job, DdlJobState.FAILED);
    }
    return stateMachine.transition(job, DdlJobState.FAILED);
  }

  private static void requireAddIndex(DdlJob job) {
    Objects.requireNonNull(job, "job == null");
    if (!DDL_ADD_INDEX.equals(job.getDdlType())) {
      throw new IllegalArgumentException("unsupported DDL type: "
          + job.getDdlType());
    }
  }
}
