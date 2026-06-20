package net.xdob.vexra.adb.db;

/**
 * ADB 控制面 route epoch 观察结果。
 *
 * <p>SQL server 或 runtime session 持有本地路由快照时，可用该对象判断控制面
 * 是否已经发布了更新的 region 快照。它只描述一次观察结果，不持有网络连接。</p>
 */
public final class AdbRouteWatch {
  private final long lastSeenEpoch;
  private final long currentEpoch;
  private final boolean routeChanged;
  private final long observedAtMillis;

  /**
   * 创建 route watch 结果。
   *
   * @param lastSeenEpoch 调用方观察前持有的 route epoch
   * @param currentEpoch 控制面当前 route epoch
   * @param routeChanged 是否存在更新路由
   * @param observedAtMillis 观察发生时间
   */
  public AdbRouteWatch(long lastSeenEpoch, long currentEpoch,
      boolean routeChanged, long observedAtMillis) {
    this.lastSeenEpoch = nonNegative(lastSeenEpoch, "lastSeenEpoch");
    this.currentEpoch = nonNegative(currentEpoch, "currentEpoch");
    this.routeChanged = routeChanged;
    this.observedAtMillis = nonNegative(observedAtMillis,
        "observedAtMillis");
  }

  public long getLastSeenEpoch() {
    return lastSeenEpoch;
  }

  public long getCurrentEpoch() {
    return currentEpoch;
  }

  public boolean isRouteChanged() {
    return routeChanged;
  }

  public long getObservedAtMillis() {
    return observedAtMillis;
  }

  private static long nonNegative(long value, String fieldName) {
    if (value < 0) {
      throw new IllegalArgumentException(fieldName + " is negative: "
          + value);
    }
    return value;
  }
}
