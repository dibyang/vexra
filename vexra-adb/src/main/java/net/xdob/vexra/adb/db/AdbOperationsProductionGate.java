package net.xdob.vexra.adb.db;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * ADB GA-05 安装与运维产品化发布门禁。
 *
 * <p>门禁校验部署、预检、备份恢复、滚动升级和诊断包证据是否完整。它不执行命令，
 * 也不修改节点状态；后续 release profile 可把实际命令输出转换为
 * {@link AdbOperationsProductionReport} 后复用该规则。</p>
 */
public final class AdbOperationsProductionGate {

  /**
   * 评估 GA-05 安装运维生产化报告。
   *
   * @param report 安装运维结构化报告
   * @return 通过状态和失败原因
   */
  public AdbLongRunStressEvaluation evaluate(
      AdbOperationsProductionReport report) {
    Objects.requireNonNull(report, "report == null");
    List<String> reasons = new ArrayList<>();
    if (!report.isPreflightPassed()) {
      reasons.add("cluster preflight did not pass");
    }
    if (!report.isTwoDataOneWitnessTopologyValidated()) {
      reasons.add("2 data + 1 witness topology was not validated");
    }
    if (!report.isSecureDefaultsEnabled()) {
      reasons.add("secure defaults are not enabled");
    }
    if (!report.isRuntimeScriptsPresent()) {
      reasons.add("runtime operations scripts are missing");
    }
    if (!report.isFullBackupRestorePassed()) {
      reasons.add("FULL backup/restore drill did not pass");
    }
    if (!report.isBackupRestoreChecksumMatched()) {
      reasons.add("backup/restore checksum does not match");
    }
    if (!report.isRollingUpgradeRunbookGenerated()) {
      reasons.add("rolling-upgrade runbook is missing");
    }
    if (!report.isRollingUpgradeRollbackStepsPresent()) {
      reasons.add("rolling-upgrade rollback steps are missing");
    }
    if (!report.isDoctorBundleGenerated()) {
      reasons.add("doctor diagnostic bundle is missing");
    }
    return new AdbLongRunStressEvaluation(reasons);
  }
}
