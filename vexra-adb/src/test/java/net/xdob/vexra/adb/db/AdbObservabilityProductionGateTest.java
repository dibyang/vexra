package net.xdob.vexra.adb.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADB GA-06 可观测性与诊断生产化门禁测试。
 *
 * <p>测试覆盖诊断包、敏感配置脱敏、日志、操作报告、runtime metrics、system table、
 * SQL 诊断、后台任务诊断和 live runtime 端点状态；任一关键证据缺失都不能进入生产候选。</p>
 */
class AdbObservabilityProductionGateTest {

  /**
   * 验证完整 GA-06 诊断证据可以通过门禁。
   */
  @Test
  void shouldPassCompleteObservabilityProductionReport() {
    AdbLongRunStressEvaluation evaluation =
        new AdbObservabilityProductionGate().evaluate(completeReport());

    assertTrue(evaluation.isPassed());
  }

  /**
   * 验证诊断包、脱敏或日志证据缺失时不能通过门禁。
   */
  @Test
  void shouldRejectWhenBundleRedactionOrLogsAreIncomplete() {
    AdbLongRunStressEvaluation evaluation =
        new AdbObservabilityProductionGate().evaluate(newReport(false,
            false, false, true, true, true, true, true, true));

    assertFalse(evaluation.isPassed());
    assertTrue(evaluation.getFailureReasons().contains(
        "diagnostic bundle was not generated"));
    assertTrue(evaluation.getFailureReasons().contains(
        "sensitive config was not redacted"));
    assertTrue(evaluation.getFailureReasons().contains(
        "log tail evidence is missing"));
  }

  /**
   * 验证 runtime、system table、SQL 或后台任务诊断证据缺失时不能通过门禁。
   */
  @Test
  void shouldRejectWhenRuntimeSqlOrWorkerEvidenceIsIncomplete() {
    AdbLongRunStressEvaluation evaluation =
        new AdbObservabilityProductionGate().evaluate(newReport(true,
            true, true, true, false, false, false, false, true));

    assertFalse(evaluation.isPassed());
    assertTrue(evaluation.getFailureReasons().contains(
        "runtime metrics are missing"));
    assertTrue(evaluation.getFailureReasons().contains(
        "system table evidence is missing"));
    assertTrue(evaluation.getFailureReasons().contains(
        "SQL diagnostics are missing"));
    assertTrue(evaluation.getFailureReasons().contains(
        "background worker diagnostics are missing"));
  }

  /**
   * 验证操作报告或 live runtime 端点状态缺失时不能通过门禁。
   */
  @Test
  void shouldRejectWhenReportsOrLiveRuntimeStatusAreIncomplete() {
    AdbLongRunStressEvaluation evaluation =
        new AdbObservabilityProductionGate().evaluate(newReport(true,
            true, true, false, true, true, true, true, false));

    assertFalse(evaluation.isPassed());
    assertTrue(evaluation.getFailureReasons().contains(
        "operation reports are missing"));
    assertTrue(evaluation.getFailureReasons().contains(
        "live runtime endpoint status is missing"));
  }

  static AdbObservabilityProductionReport completeReport() {
    return newReport(true, true, true, true, true, true, true, true,
        true);
  }

  private static AdbObservabilityProductionReport newReport(
      boolean diagnosticBundleGenerated, boolean sensitiveConfigRedacted,
      boolean logsCollected, boolean operationReportsCollected,
      boolean runtimeMetricsCollected, boolean systemTablesExposed,
      boolean sqlDiagnosticsCollected,
      boolean backgroundWorkerDiagnosticsCollected,
      boolean liveRuntimeEndpointStatusRecorded) {
    return new AdbObservabilityProductionReport("ga06",
        diagnosticBundleGenerated, sensitiveConfigRedacted, logsCollected,
        operationReportsCollected, runtimeMetricsCollected,
        systemTablesExposed, sqlDiagnosticsCollected,
        backgroundWorkerDiagnosticsCollected,
        liveRuntimeEndpointStatusRecorded);
  }
}
