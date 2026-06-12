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
    this.store = Objects.requireNonNull(store, "store == null");
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
    runBackupOperation(() -> store.restore(plan.getLocation()));
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
