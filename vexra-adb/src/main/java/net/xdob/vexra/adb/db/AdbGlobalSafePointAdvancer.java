package net.xdob.vexra.adb.db;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * ADB 全局 safe point 推进器。
 *
 * <p>该推进器把控制面 TSO、GC 保留窗口和本进程活跃事务快照串起来，统一调用
 * {@link AdbGcSafePointManager#advanceTo(long, Collection)}。它只负责进程内
 * runtime 边界，不持久化 safe point，也不实现跨进程活跃事务汇总或租约。</p>
 */
public final class AdbGlobalSafePointAdvancer {
  private final AdbGcSafePointManager safePointManager;
  private final LongSupplier candidateSafePointSupplier;
  private final Supplier<Collection<Long>> activeStartTsSupplier;

  /**
   * 创建基于控制面 TSO 的 safe point 推进器。
   *
   * @param safePointManager safe point manager
   * @param controlPlaneClient 控制面客户端
   * @param gcLifeTimeTs GC 保留窗口，候选值为 nextTimestamp - gcLifeTimeTs
   * @param activeStartTsSupplier 活跃事务 startTs 快照提供器
   */
  public AdbGlobalSafePointAdvancer(AdbGcSafePointManager safePointManager,
      AdbControlPlaneClient controlPlaneClient, long gcLifeTimeTs,
      Supplier<Collection<Long>> activeStartTsSupplier) {
    this(safePointManager, () -> Math.max(0,
        Objects.requireNonNull(controlPlaneClient,
            "controlPlaneClient == null").nextTimestamp()
            - nonNegative(gcLifeTimeTs, "gcLifeTimeTs")),
        activeStartTsSupplier);
  }

  /**
   * 创建可测试的 safe point 推进器。
   *
   * @param safePointManager safe point manager
   * @param candidateSafePointSupplier 候选 safe point 提供器
   * @param activeStartTsSupplier 活跃事务 startTs 快照提供器
   */
  public AdbGlobalSafePointAdvancer(AdbGcSafePointManager safePointManager,
      LongSupplier candidateSafePointSupplier,
      Supplier<Collection<Long>> activeStartTsSupplier) {
    this.safePointManager = Objects.requireNonNull(safePointManager,
        "safePointManager == null");
    this.candidateSafePointSupplier = Objects.requireNonNull(
        candidateSafePointSupplier, "candidateSafePointSupplier == null");
    this.activeStartTsSupplier = Objects.requireNonNull(
        activeStartTsSupplier, "activeStartTsSupplier == null");
  }

  /**
   * 执行一轮 safe point 推进。
   *
   * @return 本轮推进结果
   */
  public AdbGlobalSafePointAdvanceResult advanceOnce() {
    long previous = safePointManager.getSafePoint();
    long candidate = candidateSafePointSupplier.getAsLong();
    if (candidate < 0) {
      throw new IllegalArgumentException("candidateSafePoint is negative: "
          + candidate);
    }
    List<Long> activeStartTs = activeStartTsSnapshot();
    long target = Math.max(previous, candidate);
    try {
      long advancedTo = safePointManager.advanceTo(target, activeStartTs);
      return new AdbGlobalSafePointAdvanceResult(previous, candidate,
          advancedTo, activeStartTs, advancedTo > previous, false);
    } catch (IllegalStateException e) {
      return new AdbGlobalSafePointAdvanceResult(previous, candidate,
          previous, activeStartTs, false, true);
    }
  }

  private List<Long> activeStartTsSnapshot() {
    Collection<Long> supplied = activeStartTsSupplier.get();
    if (supplied == null || supplied.isEmpty()) {
      return new ArrayList<>();
    }
    List<Long> snapshot = new ArrayList<>();
    for (Long startTs : supplied) {
      Objects.requireNonNull(startTs, "active startTs is null");
      if (startTs < 0) {
        throw new IllegalArgumentException("active startTs is negative: "
            + startTs);
      }
      snapshot.add(startTs);
    }
    return snapshot;
  }

  private static long nonNegative(long value, String fieldName) {
    if (value < 0) {
      throw new IllegalArgumentException(fieldName + " is negative: "
          + value);
    }
    return value;
  }
}
