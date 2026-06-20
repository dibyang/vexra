package net.xdob.vexra.adb.db;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * ADB GA-06 可观测性与诊断发布门禁。
 *
 * <p>门禁只校验可观测性证据是否完整，不直接读取日志、连接 runtime 或访问数据库。这样
 * release profile 可以把 doctor bundle、runtime collector、system table smoke 和 SQL
 * 诊断摘要汇总为 {@link AdbObservabilityProductionReport}，再用同一套规则判断是否允许进入
 * 试生产。</p>
 */
public final class AdbObservabilityProductionGate {

  /**
   * 评估 GA-06 可观测性与诊断生产化报告。
   *
   * @param report 可观测性诊断结构化报告
   * @return 通过状态和失败原因
   */
  public AdbLongRunStressEvaluation evaluate(
      AdbObservabilityProductionReport report) {
    Objects.requireNonNull(report, "report == null");
    List<String> reasons = new ArrayList<>();
    if (!report.isDiagnosticBundleGenerated()) {
      reasons.add("diagnostic bundle was not generated");
    }
    if (!report.isSensitiveConfigRedacted()) {
      reasons.add("sensitive config was not redacted");
    }
    if (!report.isLogsCollected()) {
      reasons.add("log tail evidence is missing");
    }
    if (!report.isOperationReportsCollected()) {
      reasons.add("operation reports are missing");
    }
    if (!report.isRuntimeMetricsCollected()) {
      reasons.add("runtime metrics are missing");
    }
    if (!report.isSystemTablesExposed()) {
      reasons.add("system table evidence is missing");
    }
    if (!report.isSqlDiagnosticsCollected()) {
      reasons.add("SQL diagnostics are missing");
    }
    if (!report.isBackgroundWorkerDiagnosticsCollected()) {
      reasons.add("background worker diagnostics are missing");
    }
    if (!report.isLiveRuntimeEndpointStatusRecorded()) {
      reasons.add("live runtime endpoint status is missing");
    }
    return new AdbLongRunStressEvaluation(reasons);
  }
}
