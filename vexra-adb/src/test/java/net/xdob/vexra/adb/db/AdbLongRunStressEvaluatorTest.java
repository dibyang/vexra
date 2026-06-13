package net.xdob.vexra.adb.db;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADB 长稳压测报告评估器测试。
 *
 * <p>测试覆盖 ADB-Prod-06 的 release gate：完整报告通过，缺失故障、失败率超标、
 * P99 延迟超标和故障未恢复都会失败。</p>
 */
class AdbLongRunStressEvaluatorTest {

  /**
   * 验证覆盖全部指标和故障类型的报告可以通过验收。
   */
  @Test
  void shouldPassCompleteStressReport() {
    AdbLongRunStressEvaluation evaluation =
        new AdbLongRunStressEvaluator().evaluate(passingReport(), criteria());

    assertTrue(evaluation.isPassed());
    assertTrue(evaluation.getFailureReasons().isEmpty());
  }

  /**
   * 验证缺少必须故障类型时不能通过。
   */
  @Test
  void shouldFailWhenRequiredFaultScenarioIsMissing() {
    AdbLongRunStressReport report = report(10_000, 10_000, 0, 50,
        Arrays.asList(
            recovered(AdbFaultInjectionType.NETWORK_PARTITION),
            recovered(AdbFaultInjectionType.LEADER_TRANSFER),
            recovered(AdbFaultInjectionType.DISK_FAULT),
            recovered(AdbFaultInjectionType.NODE_RESTART)));

    AdbLongRunStressEvaluation evaluation =
        new AdbLongRunStressEvaluator().evaluate(report, criteria());

    assertFalse(evaluation.isPassed());
    assertTrue(evaluation.getFailureReasons().contains(
        "missing fault scenario: WITNESS_LOSS"));
  }

  /**
   * 验证失败率超过阈值时不能通过。
   */
  @Test
  void shouldFailWhenFailureRateExceedsLimit() {
    AdbLongRunStressReport report = report(10_000, 10_000, 200, 50,
        allRecoveredFaults());

    AdbLongRunStressEvaluation evaluation =
        new AdbLongRunStressEvaluator().evaluate(report, criteria());

    assertFalse(evaluation.isPassed());
    assertTrue(evaluation.getFailureReasons().contains(
        "failure rate exceeds limit"));
  }

  /**
   * 验证 P99 延迟超过阈值时不能通过。
   */
  @Test
  void shouldFailWhenP99LatencyExceedsLimit() {
    AdbLongRunStressReport report = report(10_000, 10_000, 0, 501,
        allRecoveredFaults());

    AdbLongRunStressEvaluation evaluation =
        new AdbLongRunStressEvaluator().evaluate(report, criteria());

    assertFalse(evaluation.isPassed());
    assertTrue(evaluation.getFailureReasons().contains(
        "p99 latency exceeds limit"));
  }

  /**
   * 验证故障注入后未完全恢复时不能通过。
   */
  @Test
  void shouldFailWhenFaultIsNotRecovered() {
    AdbLongRunStressReport report = report(10_000, 10_000, 0, 50,
        Arrays.asList(
            recovered(AdbFaultInjectionType.NETWORK_PARTITION),
            new AdbFaultInjectionResult(
                AdbFaultInjectionType.LEADER_TRANSFER, 2, 1, false,
                "leader transfer timed out"),
            recovered(AdbFaultInjectionType.DISK_FAULT),
            recovered(AdbFaultInjectionType.NODE_RESTART),
            recovered(AdbFaultInjectionType.WITNESS_LOSS)));

    AdbLongRunStressEvaluation evaluation =
        new AdbLongRunStressEvaluator().evaluate(report, criteria());

    assertFalse(evaluation.isPassed());
    assertTrue(evaluation.getFailureReasons().contains(
        "fault not fully recovered: LEADER_TRANSFER"));
  }

  private static AdbLongRunStressReport passingReport() {
    return report(10_000, 10_000, 0, 50, allRecoveredFaults());
  }

  private static AdbLongRunStressReport report(long durationMillis,
      long totalOperations, long failedOperations, long p99LatencyMillis,
      List<AdbFaultInjectionResult> faults) {
    return new AdbLongRunStressReport("prod06-short-run", durationMillis,
        totalOperations, failedOperations, 1_000D, 20, p99LatencyMillis,
        2, 1, 3, faults);
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
