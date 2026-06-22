package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.cluster.ops.BackupRestoreMode;
import net.xdob.vexra.cluster.ops.BackupRestorePlan;
import net.xdob.vexra.cluster.ops.ClusterHealthStatus;
import net.xdob.vexra.cluster.ops.ClusterOperationsSnapshot;
import net.xdob.vexra.cluster.region.RegionMetadata;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ADB runtime 运维桥接器。
 *
 * <p>该桥接器把 ADB 控制面 route snapshot 和底层 {@link DbStore} 的 checkpoint/restore
 * 能力暴露为最小运维闭环：健康快照、system table row、metrics 和本地备份恢复演练。
 * 它不负责真实多节点调度、对象存储上传或权限系统。</p>
 */
public final class AdbRuntimeOperationsBridge {
  private final DbStore store;
  private final TxnManager txnManager;
  private final AdbControlPlaneClient controlPlaneClient;
  private final String clusterVersion;
  private final AtomicBoolean backupRunning = new AtomicBoolean(false);

  /**
   * 创建 ADB runtime 运维桥接器。
   *
   * @param store ADB store
   * @param controlPlaneClient ADB 控制面客户端
   * @param clusterVersion 集群版本
   */
  public AdbRuntimeOperationsBridge(DbStore store,
      AdbControlPlaneClient controlPlaneClient, String clusterVersion) {
    this(store, null, controlPlaneClient, clusterVersion);
  }

  /**
   * 创建带事务管理器缓存失效能力的 ADB runtime 运维桥接器。
   *
   * <p>restore 会整体替换底层 store 的可见内容；当调用方提供当前进程使用的
   * {@link TxnManager} 时，restore 成功后会清理 committed row、row-count 和 rowId
   * hint 缓存，避免压测信任缓存模式或后续读路径复用旧 store 内容。</p>
   *
   * @param store ADB store
   * @param txnManager 需要随 restore 失效缓存的事务管理器；允许为 null
   * @param controlPlaneClient ADB 控制面客户端
   * @param clusterVersion 集群版本
   */
  public AdbRuntimeOperationsBridge(DbStore store, TxnManager txnManager,
      AdbControlPlaneClient controlPlaneClient, String clusterVersion) {
    this.store = Objects.requireNonNull(store, "store == null");
    this.txnManager = txnManager;
    this.controlPlaneClient = Objects.requireNonNull(controlPlaneClient,
        "controlPlaneClient == null");
    this.clusterVersion = clusterVersion == null ? "" : clusterVersion.trim();
  }

  /**
   * 收集当前 ADB runtime 运维快照。
   *
   * @param ddlRunning 是否存在运行中的 DDL
   * @return 集群运维快照
   */
  public ClusterOperationsSnapshot collectSnapshot(boolean ddlRunning) {
    AdbControlPlaneSnapshot routeSnapshot = controlPlaneClient.getSnapshot();
    int regionCount = routeSnapshot.getRegions().size();
    int writable = 0;
    int unavailable = 0;
    for (RegionMetadata region : routeSnapshot.getRegions()) {
      if (hasWritableLeader(region)) {
        writable++;
      } else {
        unavailable++;
      }
    }
    ClusterHealthStatus status = unavailable == 0
        ? ClusterHealthStatus.HEALTHY
        : (writable == 0 ? ClusterHealthStatus.UNAVAILABLE
        : ClusterHealthStatus.DEGRADED);
    return new ClusterOperationsSnapshot(status, regionCount, writable,
        unavailable, ddlRunning, backupRunning.get(), clusterVersion);
  }

  /**
   * 输出 system table 风格的运维行。
   *
   * @param ddlRunning 是否存在运行中的 DDL
   * @return system table row
   */
  public Map<String, String> systemTableRow(boolean ddlRunning) {
    return collectSnapshot(ddlRunning).toSystemTableRow();
  }

  /**
   * 输出 metrics 数值字段。
   *
   * @param ddlRunning 是否存在运行中的 DDL
   * @return metrics map
   */
  public Map<String, Number> metrics(boolean ddlRunning) {
    return collectSnapshot(ddlRunning).toMetrics();
  }

  /**
   * 执行本地全量备份演练。
   *
   * @param plan 备份计划，当前仅支持 FULL
   * @throws IOException 当备份失败或模式不支持时抛出
   */
  public void backup(BackupRestorePlan plan) throws IOException {
    requireMode(plan, BackupRestoreMode.FULL, "backup");
    runBackupOperation(() -> store.checkpoint(plan.getLocation()));
  }

  /**
   * 执行本地恢复演练。
   *
   * @param plan 恢复计划，当前仅支持 FULL
   * @throws IOException 当恢复失败或模式不支持时抛出
   */
  public void restore(BackupRestorePlan plan) throws IOException {
    requireMode(plan, BackupRestoreMode.FULL, "restore");
    runBackupOperation(() -> {
      store.restore(plan.getLocation());
      if (txnManager != null) {
        txnManager.invalidateStoreDerivedCaches();
      }
    });
  }

  private void runBackupOperation(BackupOperation operation) throws IOException {
    if (!backupRunning.compareAndSet(false, true)) {
      throw new IOException("backup/restore operation is already running");
    }
    try {
      operation.run();
    } finally {
      backupRunning.set(false);
    }
  }

  private static void requireMode(BackupRestorePlan plan,
      BackupRestoreMode expected, String operation) throws IOException {
    Objects.requireNonNull(plan, "plan == null");
    if (plan.getMode() != expected) {
      throw new IOException(operation + " only supports " + expected
          + " mode, actual=" + plan.getMode());
    }
  }

  private static boolean hasWritableLeader(RegionMetadata region) {
    String leaderId = region.getReplicaMetadata().getLeaderId();
    return leaderId != null && !leaderId.trim().isEmpty()
        && region.getEpoch() == region.getReplicaMetadata().getEpoch();
  }

  private interface BackupOperation {
    void run() throws IOException;
  }
}
