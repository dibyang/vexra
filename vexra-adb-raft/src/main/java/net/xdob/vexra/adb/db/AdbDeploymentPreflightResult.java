package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.ha2.AdbRClientRegistryRefreshResult;
import net.xdob.vexra.cluster.ops.ClusterHealthStatus;
import net.xdob.vexra.cluster.ops.ClusterOperationsSnapshot;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ADB 部署预检结果。
 *
 * <p>结果汇总启动命令、registry 刷新状态、集群运维快照和系统表/metrics 输出，
 * 用于部署验收和后续自动化脚本判断当前集群是否可以对外服务。</p>
 */
public final class AdbDeploymentPreflightResult {
  private final List<String> startupCommands;
  private final AdbRClientRegistryRefreshResult registryRefreshResult;
  private final ClusterOperationsSnapshot operationsSnapshot;
  private final Map<String, String> systemTableRow;
  private final Map<String, Number> metrics;

  AdbDeploymentPreflightResult(List<String> startupCommands,
      AdbRClientRegistryRefreshResult registryRefreshResult,
      ClusterOperationsSnapshot operationsSnapshot,
      Map<String, String> systemTableRow, Map<String, Number> metrics) {
    this.startupCommands = Objects.requireNonNull(startupCommands,
        "startupCommands == null");
    this.registryRefreshResult = Objects.requireNonNull(registryRefreshResult,
        "registryRefreshResult == null");
    this.operationsSnapshot = Objects.requireNonNull(operationsSnapshot,
        "operationsSnapshot == null");
    this.systemTableRow = Objects.requireNonNull(systemTableRow,
        "systemTableRow == null");
    this.metrics = Objects.requireNonNull(metrics, "metrics == null");
  }

  public List<String> getStartupCommands() {
    return startupCommands;
  }

  public AdbRClientRegistryRefreshResult getRegistryRefreshResult() {
    return registryRefreshResult;
  }

  public ClusterOperationsSnapshot getOperationsSnapshot() {
    return operationsSnapshot;
  }

  public Map<String, String> getSystemTableRow() {
    return systemTableRow;
  }

  public Map<String, Number> getMetrics() {
    return metrics;
  }

  /**
   * 判断部署预检是否达到可对外服务标准。
   *
   * @return 所有 region 健康且 leader client 均已注册时返回 true
   */
  public boolean isReadyForTraffic() {
    return operationsSnapshot.getHealthStatus() == ClusterHealthStatus.HEALTHY
        && registryRefreshResult.getRegionsWithoutLeader() == 0;
  }
}
