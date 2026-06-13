package net.xdob.vexra.adb.db;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * ADB 全局 safe point 推进结果。
 *
 * <p>该结果记录一次推进尝试的候选值、推进前后 safe point、活跃事务快照和是否被
 * 长事务阻塞，便于后续 admin API、system table 或 metrics 暴露。</p>
 */
public final class AdbGlobalSafePointAdvanceResult {
  private final long previousSafePoint;
  private final long candidateSafePoint;
  private final long safePoint;
  private final List<Long> activeStartTs;
  private final boolean advanced;
  private final boolean blockedByActiveTransaction;

  /**
   * 创建 safe point 推进结果。
   *
   * @param previousSafePoint 推进前 safe point
   * @param candidateSafePoint 本轮候选 safe point
   * @param safePoint 推进后的 safe point
   * @param activeStartTs 本轮看到的活跃事务 startTs 快照
   * @param advanced 本轮是否实际前进
   * @param blockedByActiveTransaction 是否被活跃事务阻塞
   */
  public AdbGlobalSafePointAdvanceResult(long previousSafePoint,
      long candidateSafePoint, long safePoint, List<Long> activeStartTs,
      boolean advanced, boolean blockedByActiveTransaction) {
    this.previousSafePoint = nonNegative(previousSafePoint,
        "previousSafePoint");
    this.candidateSafePoint = nonNegative(candidateSafePoint,
        "candidateSafePoint");
    this.safePoint = nonNegative(safePoint, "safePoint");
    Objects.requireNonNull(activeStartTs, "activeStartTs == null");
    this.activeStartTs = Collections.unmodifiableList(
        new ArrayList<>(activeStartTs));
    this.advanced = advanced;
    this.blockedByActiveTransaction = blockedByActiveTransaction;
  }

  public long getPreviousSafePoint() {
    return previousSafePoint;
  }

  public long getCandidateSafePoint() {
    return candidateSafePoint;
  }

  public long getSafePoint() {
    return safePoint;
  }

  public List<Long> getActiveStartTs() {
    return activeStartTs;
  }

  public boolean isAdvanced() {
    return advanced;
  }

  public boolean isBlockedByActiveTransaction() {
    return blockedByActiveTransaction;
  }

  private static long nonNegative(long value, String fieldName) {
    if (value < 0) {
      throw new IllegalArgumentException(fieldName + " is negative: "
          + value);
    }
    return value;
  }
}
