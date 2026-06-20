package net.xdob.vexra.adb.db;

import java.util.Objects;

/**
 * ADB 试生产准入报告。
 *
 * <p>该报告把 GA-07 的人工准入项结构化：release gate 是否通过、数据规模是否受限、
 * 回滚预案是否就绪、告警是否就绪、值守窗口是否就绪、已知限制是否被接受。它不执行测试，
 * 只作为 release evidence 之后的试生产决策输入。</p>
 */
public final class AdbTrialProductionAdmissionReport {
  private final String releaseId;
  private final String version;
  private final boolean releaseGatePassed;
  private final boolean dataScaleAccepted;
  private final boolean rollbackPlanReady;
  private final boolean alertingReady;
  private final boolean onCallWindowReady;
  private final boolean knownLimitationsAccepted;
  private final String notes;

  /**
   * 创建试生产准入报告。
   *
   * @param releaseId 发布或试生产批次 ID
   * @param version 发布版本
   * @param releaseGatePassed release gate 是否通过
   * @param dataScaleAccepted 是否确认小规模、非核心业务数据范围
   * @param rollbackPlanReady 备份恢复和流量切回预案是否就绪
   * @param alertingReady 关键指标和告警是否就绪
   * @param onCallWindowReady 首批试生产人工值守窗口是否就绪
   * @param knownLimitationsAccepted 用户是否接受已知限制
   * @param notes 准入备注
   */
  public AdbTrialProductionAdmissionReport(String releaseId, String version,
      boolean releaseGatePassed, boolean dataScaleAccepted,
      boolean rollbackPlanReady, boolean alertingReady,
      boolean onCallWindowReady, boolean knownLimitationsAccepted,
      String notes) {
    this.releaseId = requireText(releaseId, "releaseId");
    this.version = requireText(version, "version");
    this.releaseGatePassed = releaseGatePassed;
    this.dataScaleAccepted = dataScaleAccepted;
    this.rollbackPlanReady = rollbackPlanReady;
    this.alertingReady = alertingReady;
    this.onCallWindowReady = onCallWindowReady;
    this.knownLimitationsAccepted = knownLimitationsAccepted;
    this.notes = notes == null ? "" : notes.trim();
  }

  public String getReleaseId() {
    return releaseId;
  }

  public String getVersion() {
    return version;
  }

  public boolean isReleaseGatePassed() {
    return releaseGatePassed;
  }

  public boolean isDataScaleAccepted() {
    return dataScaleAccepted;
  }

  public boolean isRollbackPlanReady() {
    return rollbackPlanReady;
  }

  public boolean isAlertingReady() {
    return alertingReady;
  }

  public boolean isOnCallWindowReady() {
    return onCallWindowReady;
  }

  public boolean isKnownLimitationsAccepted() {
    return knownLimitationsAccepted;
  }

  public String getNotes() {
    return notes;
  }

  private static String requireText(String value, String fieldName) {
    Objects.requireNonNull(value, fieldName + " == null");
    String text = value.trim();
    if (text.isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return text;
  }
}
