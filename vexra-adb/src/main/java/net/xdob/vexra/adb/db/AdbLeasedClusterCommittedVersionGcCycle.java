package net.xdob.vexra.adb.db;

import java.sql.SQLException;
import java.util.Objects;

/**
 * ADB 租约保护的集群 committed version GC cycle。
 *
 * <p>该类把 safe point 推进、safe point lease 和集群级 committed version GC
 * 分片调度串成一轮可测试的闭环：先抢占/续租并推进 safe point，只有当前 worker
 * 拿到租约时才使用本轮持久化的 safe point 派发 region GC。它仍然只是单进程
 * 编排边界，真实远程传输、leader fencing 和 PD/etcd 级租约由后续部署层实现。</p>
 */
public final class AdbLeasedClusterCommittedVersionGcCycle {
  private final AdbLeasedGlobalSafePointAdvancer safePointAdvancer;
  private final AdbClusterCommittedVersionGcScheduler gcScheduler;

  /**
   * 创建租约保护的集群 GC cycle。
   *
   * @param safePointAdvancer 带租约的 safe point 推进器
   * @param gcScheduler 集群级 committed version GC 调度器
   */
  public AdbLeasedClusterCommittedVersionGcCycle(
      AdbLeasedGlobalSafePointAdvancer safePointAdvancer,
      AdbClusterCommittedVersionGcScheduler gcScheduler) {
    this.safePointAdvancer = Objects.requireNonNull(safePointAdvancer,
        "safePointAdvancer == null");
    this.gcScheduler = Objects.requireNonNull(gcScheduler,
        "gcScheduler == null");
  }

  /**
   * 执行一轮租约保护的集群 GC。
   *
   * @param limit 每个 region 单轮最多删除多少个历史版本，0 表示不限
   * @param timeoutMillis 整轮 region GC 调度超时，0 表示不限
   * @return 本轮 safe point 与 region GC 调度结果
   * @throws SQLException safe point 租约/持久化失败，或 region GC 调度失败时抛出
   */
  public AdbLeasedClusterCommittedVersionGcCycleResult runOnce(int limit,
      long timeoutMillis) throws SQLException {
    AdbLeasedGlobalSafePointAdvanceResult safePointResult =
        safePointAdvancer.advanceOnce();
    if (!safePointResult.isLeaseAcquired()) {
      return new AdbLeasedClusterCommittedVersionGcCycleResult(
          safePointResult, null);
    }
    long safePoint = safePointResult.getLeaseRecord().getSafePoint();
    AdbClusterCommittedVersionGcResult gcResult =
        gcScheduler.cleanOnce(safePoint, limit, timeoutMillis);
    return new AdbLeasedClusterCommittedVersionGcCycleResult(
        safePointResult, gcResult);
  }
}
