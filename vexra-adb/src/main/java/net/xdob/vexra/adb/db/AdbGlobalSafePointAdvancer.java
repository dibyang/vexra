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
  private final Supplier<Collection<Long>> backupSafePointSupplier;

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
    this(safePointManager, candidateSafePointSupplier, activeStartTsSupplier,
        java.util.Collections::emptyList);
  }

  /**
   * 创建带备份 safe point 保护的可测试推进器。
   *
   * @param safePointManager safe point manager
   * @param candidateSafePointSupplier 候选 safe point 提供器
   * @param activeStartTsSupplier 活跃事务 startTs 快照提供器
   * @param backupSafePointSupplier 备份保护 safe point 快照提供器
   */
  public AdbGlobalSafePointAdvancer(AdbGcSafePointManager safePointManager,
      LongSupplier candidateSafePointSupplier,
      Supplier<Collection<Long>> activeStartTsSupplier,
      Supplier<Collection<Long>> backupSafePointSupplier) {
    this.safePointManager = Objects.requireNonNull(safePointManager,
        "safePointManager == null");
    this.candidateSafePointSupplier = Objects.requireNonNull(
        candidateSafePointSupplier, "candidateSafePointSupplier == null");
    this.activeStartTsSupplier = Objects.requireNonNull(
        activeStartTsSupplier, "activeStartTsSupplier == null");
    this.backupSafePointSupplier = Objects.requireNonNull(
        backupSafePointSupplier, "backupSafePointSupplier == null");
  }

  /**
   * 创建带备份 safe point 注册表的推进器。
   *
   * @param safePointManager safe point manager
   * @param candidateSafePointSupplier 候选 safe point 提供器
   * @param activeStartTsSupplier 活跃事务 startTs 快照提供器
   * @param backupSafePointRegistry 备份 safe point 注册表
   */
  public AdbGlobalSafePointAdvancer(AdbGcSafePointManager safePointManager,
      LongSupplier candidateSafePointSupplier,
      Supplier<Collection<Long>> activeStartTsSupplier,
      AdbBackupSafePointRegistry backupSafePointRegistry) {
    this(safePointManager, candidateSafePointSupplier, activeStartTsSupplier,
        Objects.requireNonNull(backupSafePointRegistry,
            "backupSafePointRegistry == null")::safePointSnapshot);
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
    List<Long> backupSafePoints = backupSafePointSnapshot();
    long target = Math.max(previous, candidate);
    if (reachesProtectedPoint(target, activeStartTs)) {
      return new AdbGlobalSafePointAdvanceResult(previous, candidate,
          previous, activeStartTs, backupSafePoints, false, true, false);
    }
    if (reachesProtectedPoint(target, backupSafePoints)) {
      return new AdbGlobalSafePointAdvanceResult(previous, candidate,
          previous, activeStartTs, backupSafePoints, false, false, true);
    }
    try {
      long advancedTo = safePointManager.advanceTo(target, activeStartTs);
      return new AdbGlobalSafePointAdvanceResult(previous, candidate,
          advancedTo, activeStartTs, backupSafePoints, advancedTo > previous,
          false, false);
    } catch (IllegalStateException e) {
      return new AdbGlobalSafePointAdvanceResult(previous, candidate,
          previous, activeStartTs, backupSafePoints, false, true, false);
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

  private List<Long> backupSafePointSnapshot() {
    Collection<Long> supplied = backupSafePointSupplier.get();
    if (supplied == null || supplied.isEmpty()) {
      return new ArrayList<>();
    }
    List<Long> snapshot = new ArrayList<>();
    for (Long safePoint : supplied) {
      Objects.requireNonNull(safePoint, "backup safe point is null");
      if (safePoint < 0) {
        throw new IllegalArgumentException("backup safe point is negative: "
            + safePoint);
      }
      snapshot.add(safePoint);
    }
    return snapshot;
  }

  private static boolean reachesProtectedPoint(long target,
      List<Long> protectedPoints) {
    for (Long protectedPoint : protectedPoints) {
      if (target >= protectedPoint) {
        return true;
      }
    }
    return false;
  }

  private static long nonNegative(long value, String fieldName) {
    if (value < 0) {
      throw new IllegalArgumentException(fieldName + " is negative: "
          + value);
    }
    return value;
  }
}
