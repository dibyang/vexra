package net.xdob.vexra.adb.db;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ADB 集群级 committed version GC 调度结果。
 *
 * <p>该结果汇总一次按 region 分片派发后的成功清理结果和跳过数量，供测试、
 * 运维 API、system table 或 metrics 暴露。失败和超时不会进入该结果，而是由
 * 调度器以带 regionId 的 {@link java.sql.SQLException} 返回给调用方。</p>
 */
public final class AdbClusterCommittedVersionGcResult {
  private final long routeEpoch;
  private final int scheduledRegions;
  private final int completedRegions;
  private final int skippedNoLeaderRegions;
  private final int scannedVersions;
  private final int deletedVersions;
  private final Map<String, AdbGcCleanResult> regionResults;

  /**
   * 创建集群级 GC 调度结果。
   *
   * @param routeEpoch 控制面路由快照 epoch
   * @param scheduledRegions 已派发 region 数量
   * @param completedRegions 成功完成 region 数量
   * @param skippedNoLeaderRegions 因无 leader 跳过的 region 数量
   * @param regionResults regionId 到单 region GC 结果的映射
   */
  public AdbClusterCommittedVersionGcResult(long routeEpoch,
      int scheduledRegions, int completedRegions, int skippedNoLeaderRegions,
      Map<String, AdbGcCleanResult> regionResults) {
    this.routeEpoch = nonNegative(routeEpoch, "routeEpoch");
    this.scheduledRegions = nonNegative(scheduledRegions,
        "scheduledRegions");
    this.completedRegions = nonNegative(completedRegions,
        "completedRegions");
    this.skippedNoLeaderRegions = nonNegative(skippedNoLeaderRegions,
        "skippedNoLeaderRegions");
    Objects.requireNonNull(regionResults, "regionResults == null");
    if (completedRegions > scheduledRegions) {
      throw new IllegalArgumentException(
          "completedRegions exceeds scheduledRegions");
    }
    if (regionResults.size() != completedRegions) {
      throw new IllegalArgumentException(
          "regionResults size does not match completedRegions");
    }
    this.regionResults = Collections.unmodifiableMap(
        new LinkedHashMap<>(regionResults));
    int scanned = 0;
    int deleted = 0;
    for (AdbGcCleanResult result : regionResults.values()) {
      Objects.requireNonNull(result, "region result is null");
      scanned += result.getScannedVersions();
      deleted += result.getDeletedVersions();
    }
    this.scannedVersions = scanned;
    this.deletedVersions = deleted;
  }

  public long getRouteEpoch() {
    return routeEpoch;
  }

  public int getScheduledRegions() {
    return scheduledRegions;
  }

  public int getCompletedRegions() {
    return completedRegions;
  }

  public int getSkippedNoLeaderRegions() {
    return skippedNoLeaderRegions;
  }

  public int getScannedVersions() {
    return scannedVersions;
  }

  public int getDeletedVersions() {
    return deletedVersions;
  }

  public Map<String, AdbGcCleanResult> getRegionResults() {
    return regionResults;
  }

  private static int nonNegative(int value, String fieldName) {
    if (value < 0) {
      throw new IllegalArgumentException(fieldName + " is negative: "
          + value);
    }
    return value;
  }

  private static long nonNegative(long value, String fieldName) {
    if (value < 0) {
      throw new IllegalArgumentException(fieldName + " is negative: "
          + value);
    }
    return value;
  }
}
