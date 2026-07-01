package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.ha2.AdbRClientRegistryRefreshResult;
import net.xdob.vexra.adb.ha2.AdbRClientRegistryRefresher;
import net.xdob.vexra.cluster.ops.BackupRestorePlan;
import net.xdob.vexra.cluster.ops.ClusterHealthStatus;
import net.xdob.vexra.cluster.ops.ClusterOperationsSnapshot;
import net.xdob.vexra.cluster.ops.RollingUpgradePlan;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Objects;

/**
 * ADB 部署预检与演练器。
 *
 * <p>该类把部署计划、控制面快照、RClient registry、operations system row、
 * backup/restore drill 和 rolling upgrade drill 串成同一个验收入口。它不创建真实
 * 远程连接，也不管理连接生命周期。</p>
 */
public final class AdbDeploymentDrill {
  private final AdbDeploymentPlan deploymentPlan;
  private final AdbControlPlaneClient controlPlaneClient;
  private final AdbRuntimeOperationsBridge operationsBridge;
  private final AdbRClientRegistryRefresher registryRefresher;

  /**
   * 创建 ADB 部署预检与演练器。
   *
   * @param deploymentPlan 部署计划
   * @param controlPlaneClient 控制面客户端
   * @param operationsBridge 运维桥接器
   * @param registryRefresher RClient registry 刷新器
   */
  public AdbDeploymentDrill(AdbDeploymentPlan deploymentPlan,
      AdbControlPlaneClient controlPlaneClient,
      AdbRuntimeOperationsBridge operationsBridge,
      AdbRClientRegistryRefresher registryRefresher) {
    this.deploymentPlan = Objects.requireNonNull(deploymentPlan,
        "deploymentPlan == null");
    this.controlPlaneClient = Objects.requireNonNull(controlPlaneClient,
        "controlPlaneClient == null");
    this.operationsBridge = Objects.requireNonNull(operationsBridge,
        "operationsBridge == null");
    this.registryRefresher = Objects.requireNonNull(registryRefresher,
        "registryRefresher == null");
  }

  /**
   * 执行部署预检。
   *
   * @param ddlRunning 当前是否存在运行中的 DDL
   * @return 部署预检结果
   * @throws SQLException registry 刷新失败时抛出
   */
  public AdbDeploymentPreflightResult preflight(boolean ddlRunning)
      throws SQLException {
    AdbRClientRegistryRefreshResult refreshResult =
        registryRefresher.refresh(controlPlaneClient.getSnapshot());
    ClusterOperationsSnapshot snapshot =
        operationsBridge.collectSnapshot(ddlRunning);
    return new AdbDeploymentPreflightResult(deploymentPlan.startupCommands(),
        refreshResult, snapshot, operationsBridge.systemTableRow(ddlRunning),
        operationsBridge.metrics(ddlRunning));
  }

  /**
   * 执行 FULL backup + restore 演练。
   *
   * @param plan 备份恢复计划
   * @throws IOException 备份或恢复失败时抛出
   */
  public void runBackupRestoreDrill(BackupRestorePlan plan)
      throws IOException {
    operationsBridge.backup(plan);
    operationsBridge.restore(plan);
  }

  /**
   * 执行滚动升级演练。
   *
   * @param plan 初始滚动升级计划
   * @return 全部节点标记完成后的升级计划
   * @throws SQLException 集群无可写 region 时抛出
   */
  public RollingUpgradePlan runRollingUpgradeDrill(RollingUpgradePlan plan)
      throws SQLException {
    RollingUpgradePlan current = Objects.requireNonNull(plan,
        "plan == null");
    while (!current.isComplete()) {
      requireWritableCluster();
      current = current.markUpgraded(current.nextNode());
    }
    return current;
  }

  private void requireWritableCluster() throws SQLException {
    ClusterOperationsSnapshot snapshot = operationsBridge.collectSnapshot(false);
    if (snapshot.getHealthStatus() == ClusterHealthStatus.UNAVAILABLE
        || snapshot.getWritableRegionCount() <= 0) {
      throw new SQLException("cluster is not writable during rolling upgrade");
    }
  }
}
