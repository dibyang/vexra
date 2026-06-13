package net.xdob.vexra.adb.db;

import net.xdob.vexra.cluster.region.KeyRange;

import java.util.Objects;

/**
 * ADB region committed version GC 请求。
 *
 * <p>该对象是集群级 GC 分片调度和后续 RPC 传输之间的边界模型。它携带
 * region 路由版本、leader、key range、safe point 和单轮删除上限，使后续
 * region-scoped cleaner 可以直接复用同一请求，不需要改外层调度协议。</p>
 */
public final class AdbRegionCommittedVersionGcRequest {
  private final String regionId;
  private final long regionEpoch;
  private final String leaderId;
  private final long routeEpoch;
  private final KeyRange range;
  private final long safePoint;
  private final int limit;
  private final long timeoutMillis;

  /**
   * 创建 region committed version GC 请求。
   *
   * @param regionId region 标识
   * @param regionEpoch region epoch
   * @param leaderId 当前 leader 标识
   * @param routeEpoch 控制面路由快照 epoch
   * @param range region key range
   * @param safePoint GC safe point
   * @param limit 单轮最多删除多少个历史版本，0 表示不限
   * @param timeoutMillis 请求超时，0 表示不限
   */
  public AdbRegionCommittedVersionGcRequest(String regionId,
      long regionEpoch, String leaderId, long routeEpoch, KeyRange range,
      long safePoint, int limit, long timeoutMillis) {
    this.regionId = normalize(regionId, "regionId");
    this.regionEpoch = nonNegative(regionEpoch, "regionEpoch");
    this.leaderId = normalize(leaderId, "leaderId");
    this.routeEpoch = nonNegative(routeEpoch, "routeEpoch");
    this.range = Objects.requireNonNull(range, "range == null");
    this.safePoint = nonNegative(safePoint, "safePoint");
    this.limit = nonNegative(limit, "limit");
    this.timeoutMillis = nonNegative(timeoutMillis, "timeoutMillis");
  }

  public String getRegionId() {
    return regionId;
  }

  public long getRegionEpoch() {
    return regionEpoch;
  }

  public String getLeaderId() {
    return leaderId;
  }

  public long getRouteEpoch() {
    return routeEpoch;
  }

  public KeyRange getRange() {
    return range;
  }

  public long getSafePoint() {
    return safePoint;
  }

  public int getLimit() {
    return limit;
  }

  public long getTimeoutMillis() {
    return timeoutMillis;
  }

  private static String normalize(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return value.trim();
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
