package net.xdob.vexra.adb.db;

/**
 * ADB GA-06 可观测性与诊断结构化验收报告。
 *
 * <p>该报告固定 release profile 或 CI 需要提交的诊断证据字段：诊断包、敏感配置脱敏、
 * 日志尾部、操作报告、runtime metrics、system table、SQL 诊断、后台任务诊断和 live
 * runtime 端点状态。它不主动采集运行时数据；doctor、runtime collector 或发布脚本应把真实
 * 证据映射到这个对象，再交给 {@link AdbObservabilityProductionGate} 判定。</p>
 */
public final class AdbObservabilityProductionReport {
  private final String scenarioName;
  private final boolean diagnosticBundleGenerated;
  private final boolean sensitiveConfigRedacted;
  private final boolean logsCollected;
  private final boolean operationReportsCollected;
  private final boolean runtimeMetricsCollected;
  private final boolean systemTablesExposed;
  private final boolean sqlDiagnosticsCollected;
  private final boolean backgroundWorkerDiagnosticsCollected;
  private final boolean liveRuntimeEndpointStatusRecorded;

  /**
   * 创建可观测性与诊断生产化验收报告。
   *
   * @param scenarioName 场景名称或执行批次名称
   * @param diagnosticBundleGenerated doctor 诊断包是否生成
   * @param sensitiveConfigRedacted 诊断包中的敏感配置是否完成脱敏
   * @param logsCollected 是否采集关键日志尾部或缺失日志说明
   * @param operationReportsCollected 是否归档操作报告或 evidence 摘要
   * @param runtimeMetricsCollected 是否采集 runtime 指标
   * @param systemTablesExposed 是否验证系统表诊断入口
   * @param sqlDiagnosticsCollected 是否采集 SQL 慢查询/失败摘要
   * @param backgroundWorkerDiagnosticsCollected 是否采集后台任务最后状态
   * @param liveRuntimeEndpointStatusRecorded 是否记录 live runtime 端点状态
   */
  public AdbObservabilityProductionReport(String scenarioName,
      boolean diagnosticBundleGenerated, boolean sensitiveConfigRedacted,
      boolean logsCollected, boolean operationReportsCollected,
      boolean runtimeMetricsCollected, boolean systemTablesExposed,
      boolean sqlDiagnosticsCollected,
      boolean backgroundWorkerDiagnosticsCollected,
      boolean liveRuntimeEndpointStatusRecorded) {
    this.scenarioName = normalize(scenarioName, "scenarioName");
    this.diagnosticBundleGenerated = diagnosticBundleGenerated;
    this.sensitiveConfigRedacted = sensitiveConfigRedacted;
    this.logsCollected = logsCollected;
    this.operationReportsCollected = operationReportsCollected;
    this.runtimeMetricsCollected = runtimeMetricsCollected;
    this.systemTablesExposed = systemTablesExposed;
    this.sqlDiagnosticsCollected = sqlDiagnosticsCollected;
    this.backgroundWorkerDiagnosticsCollected =
        backgroundWorkerDiagnosticsCollected;
    this.liveRuntimeEndpointStatusRecorded = liveRuntimeEndpointStatusRecorded;
  }

  public String getScenarioName() {
    return scenarioName;
  }

  public boolean isDiagnosticBundleGenerated() {
    return diagnosticBundleGenerated;
  }

  public boolean isSensitiveConfigRedacted() {
    return sensitiveConfigRedacted;
  }

  public boolean isLogsCollected() {
    return logsCollected;
  }

  public boolean isOperationReportsCollected() {
    return operationReportsCollected;
  }

  public boolean isRuntimeMetricsCollected() {
    return runtimeMetricsCollected;
  }

  public boolean isSystemTablesExposed() {
    return systemTablesExposed;
  }

  public boolean isSqlDiagnosticsCollected() {
    return sqlDiagnosticsCollected;
  }

  public boolean isBackgroundWorkerDiagnosticsCollected() {
    return backgroundWorkerDiagnosticsCollected;
  }

  public boolean isLiveRuntimeEndpointStatusRecorded() {
    return liveRuntimeEndpointStatusRecorded;
  }

  private static String normalize(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return value.trim();
  }
}
