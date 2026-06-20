package net.xdob.vexra.adb.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADB 试生产准入门禁测试。
 *
 * <p>覆盖 GA-07 中 release gate 之后的人工准入项，避免未准备回滚、告警、值守或限制确认时
 * 误进入试生产。</p>
 */
class AdbTrialProductionAdmissionGateTest {
  /**
   * 验证所有准入项满足时通过。
   */
  @Test
  void shouldPassWhenAllAdmissionItemsAreReady() {
    AdbLongRunStressEvaluation evaluation =
        new AdbTrialProductionAdmissionGate().evaluate(report(true, true,
            true, true, true, true));

    assertTrue(evaluation.isPassed());
  }

  /**
   * 验证缺失任一准入项时失败并保留原因。
   */
  @Test
  void shouldRejectWhenAdmissionItemsAreMissing() {
    AdbLongRunStressEvaluation evaluation =
        new AdbTrialProductionAdmissionGate().evaluate(report(false, false,
            false, false, false, false));

    assertFalse(evaluation.isPassed());
    assertTrue(evaluation.getFailureReasons().contains(
        "release gate did not pass"));
    assertTrue(evaluation.getFailureReasons().contains(
        "rollback plan is not ready"));
    assertTrue(evaluation.getFailureReasons().contains(
        "known limitations are not accepted"));
  }

  private static AdbTrialProductionAdmissionReport report(
      boolean releaseGatePassed, boolean dataScaleAccepted,
      boolean rollbackPlanReady, boolean alertingReady,
      boolean onCallWindowReady, boolean knownLimitationsAccepted) {
    return new AdbTrialProductionAdmissionReport("rel-001", "0.7.0",
        releaseGatePassed, dataScaleAccepted, rollbackPlanReady, alertingReady,
        onCallWindowReady, knownLimitationsAccepted, "test");
  }
}
