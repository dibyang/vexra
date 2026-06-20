package net.xdob.vexra.adb.db;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * ADB GA-04 事务最小生产化发布门禁。
 *
 * <p>门禁只校验结构化证据是否完整，不直接执行 SQL 或 Raft RPC。这样 release profile
 * 可以把本地 JUnit、多进程故障演练和长稳结果汇总为
 * {@link AdbTransactionProductionReport}，再用同一套规则判断是否允许进入试生产。</p>
 */
public final class AdbTransactionProductionGate {

  /**
   * 评估 GA-04 事务生产化报告。
   *
   * @param report 事务生产化结构化报告
   * @return 通过状态和失败原因
   */
  public AdbLongRunStressEvaluation evaluate(
      AdbTransactionProductionReport report) {
    Objects.requireNonNull(report, "report == null");
    List<String> reasons = new ArrayList<>();
    if (!report.isSingleRegionCommitPassed()) {
      reasons.add("single-region transaction path failed");
    }
    if (!report.isCrossRegionRejected()) {
      reasons.add("cross-region transaction was not rejected by default");
    }
    if (!report.isConflictSqlStateStable()) {
      reasons.add("transaction conflict SQLState is not stable");
    }
    if (!report.isLockResolveRollbackPassed()) {
      reasons.add("lock resolve rollback path failed");
    }
    if (!report.isLockResolveRollForwardPassed()) {
      reasons.add("lock resolve roll-forward path failed");
    }
    if (!report.isLockResolveIdempotent()) {
      reasons.add("lock resolve is not idempotent");
    }
    if (!report.isActiveTransactionSafePointProtected()) {
      reasons.add("safe point does not protect active transactions");
    }
    if (!report.isBackupSafePointProtected()) {
      reasons.add("safe point does not protect backups");
    }
    if (!report.isGcKeepsLatestCommittedVersion()) {
      reasons.add("GC does not keep latest committed version");
    }
    return new AdbLongRunStressEvaluation(reasons);
  }
}
