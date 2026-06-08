package net.xdob.vexra.cluster.ops;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 集群运维观测快照。
 *
 * <p>该快照聚合 region、可写性、DDL、备份和版本信息，可用于系统表、metrics、
 * 发布检查和灾难恢复演练。</p>
 */
public final class ClusterOperationsSnapshot {
  private final ClusterHealthStatus healthStatus;
  private final int regionCount;
  private final int writableRegionCount;
  private final int unavailableRegionCount;
  private final boolean ddlRunning;
  private final boolean backupRunning;
  private final String clusterVersion;

  /**
   * 创建集群运维快照。
   *
   * @param healthStatus 健康状态
   * @param regionCount region 总数
   * @param writableRegionCount 可写 region 数
   * @param unavailableRegionCount 不可用 region 数
   * @param ddlRunning 是否有 DDL 正在运行
   * @param backupRunning 是否有备份正在运行
   * @param clusterVersion 集群版本
   */
  public ClusterOperationsSnapshot(ClusterHealthStatus healthStatus,
      int regionCount, int writableRegionCount, int unavailableRegionCount,
      boolean ddlRunning, boolean backupRunning, String clusterVersion) {
    this.healthStatus = Objects.requireNonNull(healthStatus,
        "healthStatus == null");
    if (regionCount < 0 || writableRegionCount < 0
        || unavailableRegionCount < 0) {
      throw new IllegalArgumentException("region counts must be non-negative");
    }
    if (writableRegionCount > regionCount
        || unavailableRegionCount > regionCount) {
      throw new IllegalArgumentException("region counts exceed total");
    }
    this.regionCount = regionCount;
    this.writableRegionCount = writableRegionCount;
    this.unavailableRegionCount = unavailableRegionCount;
    this.ddlRunning = ddlRunning;
    this.backupRunning = backupRunning;
    this.clusterVersion = clusterVersion == null ? "" : clusterVersion.trim();
  }

  public ClusterHealthStatus getHealthStatus() {
    return healthStatus;
  }

  public int getRegionCount() {
    return regionCount;
  }

  public int getWritableRegionCount() {
    return writableRegionCount;
  }

  public int getUnavailableRegionCount() {
    return unavailableRegionCount;
  }

  public boolean isDdlRunning() {
    return ddlRunning;
  }

  public boolean isBackupRunning() {
    return backupRunning;
  }

  public String getClusterVersion() {
    return clusterVersion;
  }

  /**
   * 转换为系统表行。
   *
   * @return 字段名到字符串值的有序映射
   */
  public Map<String, String> toSystemTableRow() {
    Map<String, String> row = new LinkedHashMap<>();
    row.put("health_status", healthStatus.name());
    row.put("region_count", Integer.toString(regionCount));
    row.put("writable_region_count", Integer.toString(writableRegionCount));
    row.put("unavailable_region_count", Integer.toString(unavailableRegionCount));
    row.put("ddl_running", Boolean.toString(ddlRunning));
    row.put("backup_running", Boolean.toString(backupRunning));
    row.put("cluster_version", clusterVersion);
    return row;
  }

  /**
   * 转换为 metrics 数值字段。
   *
   * @return 指标名到数值的有序映射
   */
  public Map<String, Number> toMetrics() {
    Map<String, Number> metrics = new LinkedHashMap<>();
    metrics.put("vexra_cluster_region_count", regionCount);
    metrics.put("vexra_cluster_writable_region_count", writableRegionCount);
    metrics.put("vexra_cluster_unavailable_region_count",
        unavailableRegionCount);
    metrics.put("vexra_cluster_ddl_running", ddlRunning ? 1 : 0);
    metrics.put("vexra_cluster_backup_running", backupRunning ? 1 : 0);
    metrics.put("vexra_cluster_health_status", healthStatus.ordinal());
    return metrics;
  }
}
