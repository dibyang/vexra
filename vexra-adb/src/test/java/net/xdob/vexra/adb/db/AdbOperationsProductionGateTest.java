package net.xdob.vexra.adb.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADB GA-05 安装运维生产化门禁测试。
 *
 * <p>测试覆盖部署预检、安全默认值、runtime 脚本、备份恢复、滚动升级回滚步骤和 doctor
 * 诊断包等证据；任一关键证据缺失都不能进入生产候选。</p>
 */
class AdbOperationsProductionGateTest {

  /**
   * 验证完整 GA-05 运维证据可以通过门禁。
   */
  @Test
  void shouldPassCompleteOperationsProductionReport() {
    AdbLongRunStressEvaluation evaluation =
        new AdbOperationsProductionGate().evaluate(completeReport());

    assertTrue(evaluation.isPassed());
  }

  /**
   * 验证预检、安全默认值或 runtime 脚本缺失时不能通过门禁。
   */
  @Test
  void shouldRejectWhenInstallEvidenceIsIncomplete() {
    AdbLongRunStressEvaluation evaluation =
        new AdbOperationsProductionGate().evaluate(newReport(false,
            false, false, false, true, true, true, true, true));

    assertFalse(evaluation.isPassed());
    assertTrue(evaluation.getFailureReasons().contains(
        "cluster preflight did not pass"));
    assertTrue(evaluation.getFailureReasons().contains(
        "2 data + 1 witness topology was not validated"));
    assertTrue(evaluation.getFailureReasons().contains(
        "secure defaults are not enabled"));
    assertTrue(evaluation.getFailureReasons().contains(
        "runtime operations scripts are missing"));
  }

  /**
   * 验证备份恢复或 checksum 证据缺失时不能通过门禁。
   */
  @Test
  void shouldRejectWhenBackupRestoreEvidenceIsIncomplete() {
    AdbLongRunStressEvaluation evaluation =
        new AdbOperationsProductionGate().evaluate(newReport(true,
            true, true, true, false, false, true, true, true));

    assertFalse(evaluation.isPassed());
    assertTrue(evaluation.getFailureReasons().contains(
        "FULL backup/restore drill did not pass"));
    assertTrue(evaluation.getFailureReasons().contains(
        "backup/restore checksum does not match"));
  }

  /**
   * 验证滚动升级 runbook、回滚步骤或 doctor 证据缺失时不能通过门禁。
   */
  @Test
  void shouldRejectWhenUpgradeOrDoctorEvidenceIsIncomplete() {
    AdbLongRunStressEvaluation evaluation =
        new AdbOperationsProductionGate().evaluate(newReport(true,
            true, true, true, true, true, false, false, false));

    assertFalse(evaluation.isPassed());
    assertTrue(evaluation.getFailureReasons().contains(
        "rolling-upgrade runbook is missing"));
    assertTrue(evaluation.getFailureReasons().contains(
        "rolling-upgrade rollback steps are missing"));
    assertTrue(evaluation.getFailureReasons().contains(
        "doctor diagnostic bundle is missing"));
  }

  static AdbOperationsProductionReport completeReport() {
    return newReport(true, true, true, true, true, true, true, true,
        true);
  }

  private static AdbOperationsProductionReport newReport(
      boolean preflightPassed,
      boolean twoDataOneWitnessTopologyValidated,
      boolean secureDefaultsEnabled,
      boolean runtimeScriptsPresent,
      boolean fullBackupRestorePassed,
      boolean backupRestoreChecksumMatched,
      boolean rollingUpgradeRunbookGenerated,
      boolean rollingUpgradeRollbackStepsPresent,
      boolean doctorBundleGenerated) {
    return new AdbOperationsProductionReport("ga05", preflightPassed,
        twoDataOneWitnessTopologyValidated, secureDefaultsEnabled,
        runtimeScriptsPresent, fullBackupRestorePassed,
        backupRestoreChecksumMatched, rollingUpgradeRunbookGenerated,
        rollingUpgradeRollbackStepsPresent, doctorBundleGenerated);
  }
}
