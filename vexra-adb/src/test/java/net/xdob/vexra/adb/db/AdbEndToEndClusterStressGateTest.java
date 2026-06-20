package net.xdob.vexra.adb.db;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADB 端到端集群压测门禁测试。
 *
 * <p>测试覆盖 `ADB-Run-12` 的核心验收：完整报告通过，缺失集群读写、恢复或滚动升级
 * 证据时失败；同时确保 GA-02 commit 崩溃注入和 kill/restart 恢复演练证据会被纳入
 * release gate。</p>
 */
class AdbEndToEndClusterStressGateTest {

  /**
   * 验证完整端到端集群压测报告可以通过门禁。
   */
  @Test
  void shouldPassCompleteEndToEndClusterReport() {
    AdbLongRunStressEvaluation evaluation =
        new AdbEndToEndClusterStressGate().evaluate(report(true, true, true,
            3, longRunReport(0, 50, allRecoveredFaults())), criteria());

    assertTrue(evaluation.isPassed());
  }

  /**
   * 验证缺失 SQL/region 读写闭环时不能通过门禁。
   */
  @Test
  void shouldFailWhenClusterReadWriteSmokeFails() {
    AdbLongRunStressEvaluation evaluation =
        new AdbEndToEndClusterStressGate().evaluate(report(false, true, true,
            3, longRunReport(0, 50, allRecoveredFaults())), criteria());

    assertFalse(evaluation.isPassed());
    assertTrue(evaluation.getFailureReasons().contains(
        "cluster read/write smoke failed"));
  }

  /**
   * 验证缺失恢复或滚动升级演练时不能通过门禁。
   */
  @Test
  void shouldFailWhenRecoveryOrRollingUpgradeIsMissing() {
    AdbLongRunStressEvaluation evaluation =
        new AdbEndToEndClusterStressGate().evaluate(report(true, false, false,
            0, longRunReport(0, 50, allRecoveredFaults())), criteria());

    assertFalse(evaluation.isPassed());
    assertTrue(evaluation.getFailureReasons().contains(
        "recovery drill failed"));
    assertTrue(evaluation.getFailureReasons().contains(
        "rolling upgrade drill failed"));
    assertTrue(evaluation.getFailureReasons().contains(
        "sql/region smoke cycle is missing"));
  }

  /**
   * 验证底层长稳压测失败会透传到端到端门禁。
   */
  @Test
  void shouldFailWhenLongRunMetricsFail() {
    AdbLongRunStressEvaluation evaluation =
        new AdbEndToEndClusterStressGate().evaluate(report(true, true, true,
            3, longRunReport(200, 501, allRecoveredFaults())), criteria());

    assertFalse(evaluation.isPassed());
    assertTrue(evaluation.getFailureReasons().contains(
        "failure rate exceeds limit"));
    assertTrue(evaluation.getFailureReasons().contains(
        "p99 latency exceeds limit"));
  }

  /**
   * 验证缺失 commit 崩溃注入门禁证据时不能通过端到端门禁。
   */
  @Test
  void shouldFailWhenCommitCrashInjectionGateIsMissing() {
    AdbLongRunStressEvaluation evaluation =
        new AdbEndToEndClusterStressGate().evaluate(legacyReport(true, true,
            true, 3, longRunReport(0, 50, allRecoveredFaults())),
            criteria());

    assertFalse(evaluation.isPassed());
    assertTrue(evaluation.getFailureReasons().contains(
        "commit crash injection gate is missing"));
  }

  /**
   * 验证 commit 崩溃注入门禁失败原因会透传到端到端门禁。
   */
  @Test
  void shouldFailWhenCommitCrashInjectionGateFails() {
    AdbLongRunStressEvaluation failedCrashGate =
        new AdbCommitCrashInjectionGate().evaluate(
            new AdbCommitCrashInjectionReport("missing", Arrays.asList(
                new AdbCommitCrashInjectionResult(
                    AdbCommitCrashInjectionPoint.BEFORE_PREWRITE, true,
                    null, "recovered"))));
    AdbLongRunStressEvaluation evaluation =
        new AdbEndToEndClusterStressGate().evaluate(report(true, true, true,
            3, longRunReport(0, 50, allRecoveredFaults()), failedCrashGate,
            passedRecoveryDrillEvaluation()), criteria());

    assertFalse(evaluation.isPassed());
    assertTrue(evaluation.getFailureReasons().contains(
        "missing commit crash scenario: AFTER_PREWRITE_BEFORE_RAFT"));
  }

  /**
   * 验证缺失结构化恢复演练门禁证据时不能通过端到端门禁。
   */
  @Test
  void shouldFailWhenRecoveryDrillGateIsMissing() {
    AdbLongRunStressEvaluation evaluation =
        new AdbEndToEndClusterStressGate().evaluate(
            commitOnlyReport(true, true, true, 3,
                longRunReport(0, 50, allRecoveredFaults())),
            criteria());

    assertFalse(evaluation.isPassed());
    assertTrue(evaluation.getFailureReasons().contains(
        "recovery drill gate is missing"));
  }

  /**
   * 验证结构化恢复演练门禁失败原因会透传到端到端门禁。
   */
  @Test
  void shouldFailWhenRecoveryDrillGateFails() {
    AdbLongRunStressEvaluation failedRecoveryGate =
        new AdbRecoveryDrillGate().evaluate(new AdbRecoveryDrillReport(
            "missing", Arrays.asList(new AdbRecoveryDrillResult(
            AdbRecoveryDrillScenario.KILL_LEADER, true, true, true,
            "passed"))));
    AdbLongRunStressEvaluation evaluation =
        new AdbEndToEndClusterStressGate().evaluate(report(true, true, true,
            3, longRunReport(0, 50, allRecoveredFaults()),
            passedCommitCrashEvaluation(), failedRecoveryGate), criteria());

    assertFalse(evaluation.isPassed());
    assertTrue(evaluation.getFailureReasons().contains(
        "missing recovery drill scenario: KILL_FOLLOWER"));
  }

  private static AdbEndToEndClusterStressReport report(boolean readWrite,
      boolean recovery, boolean rollingUpgrade, int smokeCycles,
      AdbLongRunStressReport longRunReport) {
    return report(readWrite, recovery, rollingUpgrade, smokeCycles,
        longRunReport, passedCommitCrashEvaluation(),
        passedRecoveryDrillEvaluation());
  }

  private static AdbEndToEndClusterStressReport report(boolean readWrite,
      boolean recovery, boolean rollingUpgrade, int smokeCycles,
      AdbLongRunStressReport longRunReport,
      AdbLongRunStressEvaluation commitCrashEvaluation,
      AdbLongRunStressEvaluation recoveryDrillEvaluation) {
    return new AdbEndToEndClusterStressReport("run12-cluster", longRunReport,
        commitCrashEvaluation, recoveryDrillEvaluation, readWrite, recovery,
        rollingUpgrade, smokeCycles);
  }

  private static AdbEndToEndClusterStressReport commitOnlyReport(
      boolean readWrite, boolean recovery, boolean rollingUpgrade,
      int smokeCycles, AdbLongRunStressReport longRunReport) {
    return new AdbEndToEndClusterStressReport("run12-cluster", longRunReport,
        passedCommitCrashEvaluation(), readWrite, recovery, rollingUpgrade,
        smokeCycles);
  }

  private static AdbEndToEndClusterStressReport legacyReport(boolean readWrite,
      boolean recovery, boolean rollingUpgrade, int smokeCycles,
      AdbLongRunStressReport longRunReport) {
    return new AdbEndToEndClusterStressReport("run12-cluster", longRunReport,
        readWrite, recovery, rollingUpgrade, smokeCycles);
  }

  private static AdbLongRunStressEvaluation passedCommitCrashEvaluation() {
    return new AdbCommitCrashInjectionGate().evaluate(
        AdbCommitCrashInjectionGateTest.completeReport());
  }

  private static AdbLongRunStressEvaluation passedRecoveryDrillEvaluation() {
    return new AdbRecoveryDrillGate().evaluate(
        AdbRecoveryDrillGateTest.completeReport());
  }

  private static AdbLongRunStressReport longRunReport(long failedOperations,
      long p99LatencyMillis, List<AdbFaultInjectionResult> faults) {
    return new AdbLongRunStressReport("run12-e2e", 10_000, 10_000,
        failedOperations, 1_000D, 20, p99LatencyMillis, 2, 1, 3, faults);
  }

  private static AdbLongRunAcceptanceCriteria criteria() {
    return new AdbLongRunAcceptanceCriteria(5_000, 5_000, 0.01D, 500,
        1, 1, 1, EnumSet.allOf(AdbFaultInjectionType.class));
  }

  private static List<AdbFaultInjectionResult> allRecoveredFaults() {
    return Arrays.asList(
        recovered(AdbFaultInjectionType.NETWORK_PARTITION),
        recovered(AdbFaultInjectionType.LEADER_TRANSFER),
        recovered(AdbFaultInjectionType.DISK_FAULT),
        recovered(AdbFaultInjectionType.NODE_RESTART),
        recovered(AdbFaultInjectionType.WITNESS_LOSS));
  }

  private static AdbFaultInjectionResult recovered(AdbFaultInjectionType type) {
    return new AdbFaultInjectionResult(type, 2, 2, true, "recovered");
  }
}
