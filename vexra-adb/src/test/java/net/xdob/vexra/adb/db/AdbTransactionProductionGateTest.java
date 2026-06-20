package net.xdob.vexra.adb.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADB GA-04 事务最小生产化门禁测试。
 *
 * <p>测试覆盖 release profile 需要的事务结构化证据：单 region 主路径、跨 region 默认拒绝、
 * 冲突 SQLState、lock resolve、safe point 和 GC 任一缺失都不能进入生产候选。</p>
 */
class AdbTransactionProductionGateTest {

  /**
   * 验证完整 GA-04 事务证据可以通过门禁。
   */
  @Test
  void shouldPassCompleteTransactionProductionReport() {
    AdbLongRunStressEvaluation evaluation =
        new AdbTransactionProductionGate().evaluate(completeReport());

    assertTrue(evaluation.isPassed());
  }

  /**
   * 验证跨 region 事务未默认拒绝时不能通过门禁。
   */
  @Test
  void shouldRejectWhenCrossRegionGuardIsMissing() {
    AdbLongRunStressEvaluation evaluation =
        new AdbTransactionProductionGate().evaluate(newReport(true, false,
            true, true, true, true, true, true, true));

    assertFalse(evaluation.isPassed());
    assertTrue(evaluation.getFailureReasons().contains(
        "cross-region transaction was not rejected by default"));
  }

  /**
   * 验证 lock resolve 证据不完整时不能通过门禁。
   */
  @Test
  void shouldRejectWhenLockResolveEvidenceIsIncomplete() {
    AdbLongRunStressEvaluation evaluation =
        new AdbTransactionProductionGate().evaluate(newReport(true, true,
            true, false, false, false, true, true, true));

    assertFalse(evaluation.isPassed());
    assertTrue(evaluation.getFailureReasons().contains(
        "lock resolve rollback path failed"));
    assertTrue(evaluation.getFailureReasons().contains(
        "lock resolve roll-forward path failed"));
    assertTrue(evaluation.getFailureReasons().contains(
        "lock resolve is not idempotent"));
  }

  /**
   * 验证 safe point 和 GC 保护缺失时不能通过门禁。
   */
  @Test
  void shouldRejectWhenSafePointOrGcProtectionIsMissing() {
    AdbLongRunStressEvaluation evaluation =
        new AdbTransactionProductionGate().evaluate(newReport(true, true,
            true, true, true, true, false, false, false));

    assertFalse(evaluation.isPassed());
    assertTrue(evaluation.getFailureReasons().contains(
        "safe point does not protect active transactions"));
    assertTrue(evaluation.getFailureReasons().contains(
        "safe point does not protect backups"));
    assertTrue(evaluation.getFailureReasons().contains(
        "GC does not keep latest committed version"));
  }

  static AdbTransactionProductionReport completeReport() {
    return newReport(true, true, true, true, true, true, true, true,
        true);
  }

  private static AdbTransactionProductionReport newReport(
      boolean singleRegionCommitPassed, boolean crossRegionRejected,
      boolean conflictSqlStateStable, boolean lockResolveRollbackPassed,
      boolean lockResolveRollForwardPassed, boolean lockResolveIdempotent,
      boolean activeTransactionSafePointProtected,
      boolean backupSafePointProtected,
      boolean gcKeepsLatestCommittedVersion) {
    return new AdbTransactionProductionReport("ga04",
        singleRegionCommitPassed, crossRegionRejected,
        conflictSqlStateStable, lockResolveRollbackPassed,
        lockResolveRollForwardPassed, lockResolveIdempotent,
        activeTransactionSafePointProtected, backupSafePointProtected,
        gcKeepsLatestCommittedVersion);
  }
}
