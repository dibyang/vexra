package net.xdob.vexra.adb.db;

import java.util.Collection;
import java.util.Objects;

/**
 * ADB GC safe point 管理器。
 *
 * <p>该类维护当前 GC safe point，并在推进时执行两个最小安全约束：
 * safe point 只能单调前进；存在活跃长事务时，新的 safe point 不能覆盖该事务的
 * startTs。当前增量只提供运行时判断入口，不直接删除历史版本。</p>
 */
public final class AdbGcSafePointManager {
  private long safePoint;

  /**
   * 创建 safe point 管理器。
   *
   * @param initialSafePoint 初始 safe point，必须非负
   */
  public AdbGcSafePointManager(long initialSafePoint) {
    if (initialSafePoint < 0) {
      throw new IllegalArgumentException("initialSafePoint is negative: "
          + initialSafePoint);
    }
    this.safePoint = initialSafePoint;
  }

  /**
   * 返回当前 safe point。
   *
   * @return 当前 safe point
   */
  public synchronized long getSafePoint() {
    return safePoint;
  }

  /**
   * 推进 safe point。
   *
   * @param nextSafePoint 新 safe point
   * @param activeStartTs 当前活跃事务的 startTs 集合
   * @return 推进后的 safe point
   */
  public synchronized long advanceTo(long nextSafePoint,
      Collection<Long> activeStartTs) {
    if (nextSafePoint < safePoint) {
      throw new IllegalArgumentException("safe point cannot move backward, "
          + "current=" + safePoint + ", next=" + nextSafePoint);
    }
    if (nextSafePoint == safePoint) {
      return safePoint;
    }
    long minActiveStartTs = minActiveStartTs(activeStartTs);
    if (minActiveStartTs >= 0 && nextSafePoint >= minActiveStartTs) {
      throw new IllegalStateException("safe point " + nextSafePoint
          + " reaches active transaction startTs " + minActiveStartTs);
    }
    safePoint = nextSafePoint;
    return safePoint;
  }

  /**
   * 判断指定 committed version 是否允许被 GC 处理。
   *
   * @param commitTs committed version 的 commit timestamp
   * @return commitTs 小于当前 safe point 时返回 true
   */
  public synchronized boolean canCollect(long commitTs) {
    if (commitTs < 0) {
      throw new IllegalArgumentException("commitTs is negative: " + commitTs);
    }
    return commitTs < safePoint;
  }

  private static long minActiveStartTs(Collection<Long> activeStartTs) {
    if (activeStartTs == null || activeStartTs.isEmpty()) {
      return -1;
    }
    long min = Long.MAX_VALUE;
    for (Long startTs : activeStartTs) {
      Objects.requireNonNull(startTs, "active startTs is null");
      if (startTs < 0) {
        throw new IllegalArgumentException("active startTs is negative: "
            + startTs);
      }
      min = Math.min(min, startTs);
    }
    return min;
  }
}
