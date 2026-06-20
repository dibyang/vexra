package net.xdob.vexra.adb.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADB GA-07 发布就绪门禁测试。
 *
 * <p>测试覆盖最终发布判断需要的阶段门禁、端到端 release profile、试生产准入、evidence
 * 归档和文档同步，避免仅凭单一 smoke 或人工确认进入生产候选。</p>
 */
class AdbReleaseReadinessGateTest {

  /**
   * 验证所有发布就绪证据满足时通过。
   */
  @Test
  void shouldPassCompleteReleaseReadinessReport() {
    AdbLongRunStressEvaluation evaluation =
        new AdbReleaseReadinessGate().evaluate(completeReport());

    assertTrue(evaluation.isPassed());
  }

  /**
   * 验证范围冻结或兼容路径缺失时拒绝发布。
   */
  @Test
  void shouldRejectWhenScopeOrCompatibilityIsMissing() {
    AdbLongRunStressEvaluation evaluation =
        new AdbReleaseReadinessGate().evaluate(newReport(false, false,
            true, true, true, true, true, true, true, true, true));

    assertFalse(evaluation.isPassed());
    assertTrue(evaluation.getFailureReasons().contains(
        "production scope is not frozen"));
    assertTrue(evaluation.getFailureReasons().contains(
        "JDBC compatibility was not validated"));
  }

  /**
   * 验证任一阶段门禁缺失时拒绝发布。
   */
  @Test
  void shouldRejectWhenPhaseGatesAreMissing() {
    AdbLongRunStressEvaluation evaluation =
        new AdbReleaseReadinessGate().evaluate(newReport(true, true,
            false, false, false, false, false, true, true, true, true));

    assertFalse(evaluation.isPassed());
    assertTrue(evaluation.getFailureReasons().contains(
        "data safety gate did not pass"));
    assertTrue(evaluation.getFailureReasons().contains(
        "control-plane gate did not pass"));
    assertTrue(evaluation.getFailureReasons().contains(
        "transaction gate did not pass"));
    assertTrue(evaluation.getFailureReasons().contains(
        "operations gate did not pass"));
    assertTrue(evaluation.getFailureReasons().contains(
        "observability gate did not pass"));
  }

  /**
   * 验证端到端、准入、归档或文档证据缺失时拒绝发布。
   */
  @Test
  void shouldRejectWhenReleaseClosureEvidenceIsMissing() {
    AdbLongRunStressEvaluation evaluation =
        new AdbReleaseReadinessGate().evaluate(newReport(true, true,
            true, true, true, true, true, false, false, false, false));

    assertFalse(evaluation.isPassed());
    assertTrue(evaluation.getFailureReasons().contains(
        "end-to-end release profile did not pass"));
    assertTrue(evaluation.getFailureReasons().contains(
        "trial production admission did not pass"));
    assertTrue(evaluation.getFailureReasons().contains(
        "release evidence was not archived"));
    assertTrue(evaluation.getFailureReasons().contains(
        "production documentation is not updated"));
  }

  static AdbReleaseReadinessReport completeReport() {
    return newReport(true, true, true, true, true, true, true, true,
        true, true, true);
  }

  private static AdbReleaseReadinessReport newReport(
      boolean productionScopeFrozen, boolean jdbcCompatibilityValidated,
      boolean dataSafetyGatePassed, boolean controlPlaneGatePassed,
      boolean transactionGatePassed, boolean operationsGatePassed,
      boolean observabilityGatePassed, boolean endToEndReleaseProfilePassed,
      boolean trialProductionAdmissionPassed,
      boolean releaseEvidenceArchived, boolean documentationUpdated) {
    return new AdbReleaseReadinessReport("rel-ga07", "0.7.0",
        productionScopeFrozen, jdbcCompatibilityValidated,
        dataSafetyGatePassed, controlPlaneGatePassed, transactionGatePassed,
        operationsGatePassed, observabilityGatePassed,
        endToEndReleaseProfilePassed, trialProductionAdmissionPassed,
        releaseEvidenceArchived, documentationUpdated);
  }
}
