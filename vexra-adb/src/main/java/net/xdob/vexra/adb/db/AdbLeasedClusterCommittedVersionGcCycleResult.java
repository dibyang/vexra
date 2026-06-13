package net.xdob.vexra.adb.db;

import java.util.Objects;
import java.util.Optional;

/**
 * ADB 租约保护的集群 committed version GC cycle 结果。
 *
 * <p>该结果记录一轮 cycle 中 safe point lease/推进结果，以及是否继续派发了
 * region 级 GC 调度。未拿到 safe point lease 时不会派发 GC，调度结果为空；
 * 拿到 lease 后即使 safe point 被长事务阻塞，也可以使用当前持久化 safe point
 * 做保守清理。</p>
 */
public final class AdbLeasedClusterCommittedVersionGcCycleResult {
  private final AdbLeasedGlobalSafePointAdvanceResult safePointResult;
  private final AdbClusterCommittedVersionGcResult gcResult;

  /**
   * 创建租约保护的集群 GC cycle 结果。
   *
   * @param safePointResult safe point lease 与推进结果
   * @param gcResult region GC 调度结果；未派发时为空
   */
  public AdbLeasedClusterCommittedVersionGcCycleResult(
      AdbLeasedGlobalSafePointAdvanceResult safePointResult,
      AdbClusterCommittedVersionGcResult gcResult) {
    this.safePointResult = Objects.requireNonNull(safePointResult,
        "safePointResult == null");
    if (!safePointResult.isLeaseAcquired() && gcResult != null) {
      throw new IllegalArgumentException(
          "gcResult must be null when safe point lease is not acquired");
    }
    this.gcResult = gcResult;
  }

  public AdbLeasedGlobalSafePointAdvanceResult getSafePointResult() {
    return safePointResult;
  }

  public boolean isGcDispatched() {
    return gcResult != null;
  }

  public Optional<AdbClusterCommittedVersionGcResult> getGcResult() {
    return Optional.ofNullable(gcResult);
  }
}
