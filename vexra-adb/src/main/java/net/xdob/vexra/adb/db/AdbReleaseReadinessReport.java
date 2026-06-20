package net.xdob.vexra.adb.db;

/**
 * ADB GA-07 发布就绪结构化验收报告。
 *
 * <p>该报告把 ADB-GA-01 到 ADB-GA-06 的阶段门禁、端到端 release profile、试生产准入、
 * evidence 归档和文档同步汇总为最终发布判断输入。它不执行任何测试、部署或诊断动作；
 * CI、发布脚本或人工审批系统应把真实执行结果转换为该对象。</p>
 */
public final class AdbReleaseReadinessReport {
  private final String releaseId;
  private final String version;
  private final boolean productionScopeFrozen;
  private final boolean jdbcCompatibilityValidated;
  private final boolean dataSafetyGatePassed;
  private final boolean controlPlaneGatePassed;
  private final boolean transactionGatePassed;
  private final boolean operationsGatePassed;
  private final boolean observabilityGatePassed;
  private final boolean endToEndReleaseProfilePassed;
  private final boolean trialProductionAdmissionPassed;
  private final boolean releaseEvidenceArchived;
  private final boolean documentationUpdated;

  /**
   * 创建发布就绪验收报告。
   *
   * @param releaseId 发布或试生产批次 ID
   * @param version 发布版本
   * @param productionScopeFrozen 是否冻结生产 MVP 范围和已知限制
   * @param jdbcCompatibilityValidated 是否验证 `jdbc:adb:*` 兼容路径
   * @param dataSafetyGatePassed 数据安全门禁是否通过
   * @param controlPlaneGatePassed 控制面最小门禁是否通过
   * @param transactionGatePassed 事务生产门禁是否通过
   * @param operationsGatePassed 安装运维门禁是否通过
   * @param observabilityGatePassed 可观测性诊断门禁是否通过
   * @param endToEndReleaseProfilePassed 端到端 release profile 是否通过
   * @param trialProductionAdmissionPassed 试生产人工准入是否通过
   * @param releaseEvidenceArchived release evidence 是否已归档
   * @param documentationUpdated quickstart、user guide、runbook、限制文档是否同步
   */
  public AdbReleaseReadinessReport(String releaseId, String version,
      boolean productionScopeFrozen, boolean jdbcCompatibilityValidated,
      boolean dataSafetyGatePassed, boolean controlPlaneGatePassed,
      boolean transactionGatePassed, boolean operationsGatePassed,
      boolean observabilityGatePassed, boolean endToEndReleaseProfilePassed,
      boolean trialProductionAdmissionPassed, boolean releaseEvidenceArchived,
      boolean documentationUpdated) {
    this.releaseId = normalize(releaseId, "releaseId");
    this.version = normalize(version, "version");
    this.productionScopeFrozen = productionScopeFrozen;
    this.jdbcCompatibilityValidated = jdbcCompatibilityValidated;
    this.dataSafetyGatePassed = dataSafetyGatePassed;
    this.controlPlaneGatePassed = controlPlaneGatePassed;
    this.transactionGatePassed = transactionGatePassed;
    this.operationsGatePassed = operationsGatePassed;
    this.observabilityGatePassed = observabilityGatePassed;
    this.endToEndReleaseProfilePassed = endToEndReleaseProfilePassed;
    this.trialProductionAdmissionPassed = trialProductionAdmissionPassed;
    this.releaseEvidenceArchived = releaseEvidenceArchived;
    this.documentationUpdated = documentationUpdated;
  }

  public String getReleaseId() {
    return releaseId;
  }

  public String getVersion() {
    return version;
  }

  public boolean isProductionScopeFrozen() {
    return productionScopeFrozen;
  }

  public boolean isJdbcCompatibilityValidated() {
    return jdbcCompatibilityValidated;
  }

  public boolean isDataSafetyGatePassed() {
    return dataSafetyGatePassed;
  }

  public boolean isControlPlaneGatePassed() {
    return controlPlaneGatePassed;
  }

  public boolean isTransactionGatePassed() {
    return transactionGatePassed;
  }

  public boolean isOperationsGatePassed() {
    return operationsGatePassed;
  }

  public boolean isObservabilityGatePassed() {
    return observabilityGatePassed;
  }

  public boolean isEndToEndReleaseProfilePassed() {
    return endToEndReleaseProfilePassed;
  }

  public boolean isTrialProductionAdmissionPassed() {
    return trialProductionAdmissionPassed;
  }

  public boolean isReleaseEvidenceArchived() {
    return releaseEvidenceArchived;
  }

  public boolean isDocumentationUpdated() {
    return documentationUpdated;
  }

  private static String normalize(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return value.trim();
  }
}
