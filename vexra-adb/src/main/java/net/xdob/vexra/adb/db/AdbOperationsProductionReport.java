package net.xdob.vexra.adb.db;

/**
 * ADB GA-05 安装与运维产品化结构化验收报告。
 *
 * <p>该报告固定生产安装运维进入 release gate 所需的证据字段：拓扑预检、安全默认值、
 * runtime 脚本、FULL backup/restore、滚动升级 runbook、回滚步骤和 doctor 诊断包。
 * 它不执行真实部署或备份；命令行、CI 或运维系统应把真实执行结果映射到该对象。</p>
 */
public final class AdbOperationsProductionReport {
  private final String scenarioName;
  private final boolean preflightPassed;
  private final boolean twoDataOneWitnessTopologyValidated;
  private final boolean secureDefaultsEnabled;
  private final boolean runtimeScriptsPresent;
  private final boolean fullBackupRestorePassed;
  private final boolean backupRestoreChecksumMatched;
  private final boolean rollingUpgradeRunbookGenerated;
  private final boolean rollingUpgradeRollbackStepsPresent;
  private final boolean doctorBundleGenerated;

  /**
   * 创建安装运维生产化验收报告。
   *
   * @param scenarioName 场景名称或执行批次名称
   * @param preflightPassed 集群预检是否通过
   * @param twoDataOneWitnessTopologyValidated 是否验证 2 data + 1 witness 拓扑
   * @param secureDefaultsEnabled 是否开启 TLS/auth/最小权限等安全默认值
   * @param runtimeScriptsPresent runtime 分发包关键脚本是否存在
   * @param fullBackupRestorePassed FULL backup/restore 是否执行成功
   * @param backupRestoreChecksumMatched 备份恢复 checksum 是否一致
   * @param rollingUpgradeRunbookGenerated 滚动升级 runbook 是否生成
   * @param rollingUpgradeRollbackStepsPresent runbook 是否包含回滚步骤
   * @param doctorBundleGenerated doctor 诊断包是否生成
   */
  public AdbOperationsProductionReport(String scenarioName,
      boolean preflightPassed,
      boolean twoDataOneWitnessTopologyValidated,
      boolean secureDefaultsEnabled,
      boolean runtimeScriptsPresent,
      boolean fullBackupRestorePassed,
      boolean backupRestoreChecksumMatched,
      boolean rollingUpgradeRunbookGenerated,
      boolean rollingUpgradeRollbackStepsPresent,
      boolean doctorBundleGenerated) {
    this.scenarioName = normalize(scenarioName, "scenarioName");
    this.preflightPassed = preflightPassed;
    this.twoDataOneWitnessTopologyValidated =
        twoDataOneWitnessTopologyValidated;
    this.secureDefaultsEnabled = secureDefaultsEnabled;
    this.runtimeScriptsPresent = runtimeScriptsPresent;
    this.fullBackupRestorePassed = fullBackupRestorePassed;
    this.backupRestoreChecksumMatched = backupRestoreChecksumMatched;
    this.rollingUpgradeRunbookGenerated = rollingUpgradeRunbookGenerated;
    this.rollingUpgradeRollbackStepsPresent =
        rollingUpgradeRollbackStepsPresent;
    this.doctorBundleGenerated = doctorBundleGenerated;
  }

  public String getScenarioName() {
    return scenarioName;
  }

  public boolean isPreflightPassed() {
    return preflightPassed;
  }

  public boolean isTwoDataOneWitnessTopologyValidated() {
    return twoDataOneWitnessTopologyValidated;
  }

  public boolean isSecureDefaultsEnabled() {
    return secureDefaultsEnabled;
  }

  public boolean isRuntimeScriptsPresent() {
    return runtimeScriptsPresent;
  }

  public boolean isFullBackupRestorePassed() {
    return fullBackupRestorePassed;
  }

  public boolean isBackupRestoreChecksumMatched() {
    return backupRestoreChecksumMatched;
  }

  public boolean isRollingUpgradeRunbookGenerated() {
    return rollingUpgradeRunbookGenerated;
  }

  public boolean isRollingUpgradeRollbackStepsPresent() {
    return rollingUpgradeRollbackStepsPresent;
  }

  public boolean isDoctorBundleGenerated() {
    return doctorBundleGenerated;
  }

  private static String normalize(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return value.trim();
  }
}
