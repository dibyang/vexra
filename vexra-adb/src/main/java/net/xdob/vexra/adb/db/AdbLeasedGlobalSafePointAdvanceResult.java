package net.xdob.vexra.adb.db;

import java.util.Objects;
import java.util.Optional;

/**
 * ADB 带租约的全局 safe point 推进结果。
 *
 * <p>该结果把本轮 worker 是否拿到 safe point lease、最终持久化的 lease 记录和
 * 底层 safe point 推进结果放在一起，便于后续 admin API、system table 或
 * metrics 区分“未持有租约跳过”和“持有租约但被长事务阻塞”。它只描述单 store
 * 内的 worker fencing 结果，不代表 PD/etcd 级线性一致租约。</p>
 */
public final class AdbLeasedGlobalSafePointAdvanceResult {
  private final boolean leaseAcquired;
  private final AdbSafePointLeaseRecord leaseRecord;
  private final AdbGlobalSafePointAdvanceResult advanceResult;

  /**
   * 创建带租约的 safe point 推进结果。
   *
   * @param leaseAcquired 本轮是否拿到或续租成功
   * @param leaseRecord 本轮结束时观察到或持久化的 lease 记录
   * @param advanceResult 底层推进结果；未拿到租约时为空
   */
  public AdbLeasedGlobalSafePointAdvanceResult(boolean leaseAcquired,
      AdbSafePointLeaseRecord leaseRecord,
      AdbGlobalSafePointAdvanceResult advanceResult) {
    if (leaseAcquired && advanceResult == null) {
      throw new IllegalArgumentException(
          "advanceResult is required when lease is acquired");
    }
    this.leaseAcquired = leaseAcquired;
    this.leaseRecord = Objects.requireNonNull(leaseRecord,
        "leaseRecord == null");
    this.advanceResult = advanceResult;
  }

  public boolean isLeaseAcquired() {
    return leaseAcquired;
  }

  public AdbSafePointLeaseRecord getLeaseRecord() {
    return leaseRecord;
  }

  public Optional<AdbGlobalSafePointAdvanceResult> getAdvanceResult() {
    return Optional.ofNullable(advanceResult);
  }
}
