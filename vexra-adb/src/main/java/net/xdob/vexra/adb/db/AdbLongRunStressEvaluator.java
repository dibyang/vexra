package net.xdob.vexra.adb.db;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ADB 长稳压测报告评估器。
 *
 * <p>评估器把长稳报告和验收标准转换成发布前的 pass/fail 判断。它不执行压测，
 * 只负责校验报告是否满足 ADB-Prod-06 的 release gate。</p>
 */
public final class AdbLongRunStressEvaluator {

  /**
   * 评估一份长稳压测报告。
   *
   * @param report 长稳压测报告
   * @param criteria 验收标准
   * @return 评估结果
   */
  public AdbLongRunStressEvaluation evaluate(AdbLongRunStressReport report,
      AdbLongRunAcceptanceCriteria criteria) {
    Objects.requireNonNull(report, "report == null");
    Objects.requireNonNull(criteria, "criteria == null");
    List<String> reasons = new ArrayList<>();
    checkBasicMetrics(report, criteria, reasons);
    checkMaintenanceCycles(report, criteria, reasons);
    checkFaultCoverage(report, criteria, reasons);
    return new AdbLongRunStressEvaluation(reasons);
  }

  private static void checkBasicMetrics(AdbLongRunStressReport report,
      AdbLongRunAcceptanceCriteria criteria, List<String> reasons) {
    if (report.getDurationMillis() < criteria.getMinDurationMillis()) {
      reasons.add("duration below minimum");
    }
    if (report.getTotalOperations() < criteria.getMinOperations()) {
      reasons.add("total operations below minimum");
    }
    if (report.failureRate() > criteria.getMaxFailureRate()) {
      reasons.add("failure rate exceeds limit");
    }
    if (report.getP99LatencyMillis() > criteria.getMaxP99LatencyMillis()) {
      reasons.add("p99 latency exceeds limit");
    }
  }

  private static void checkMaintenanceCycles(AdbLongRunStressReport report,
      AdbLongRunAcceptanceCriteria criteria, List<String> reasons) {
    if (report.getCheckpointCycles() < criteria.getMinCheckpointCycles()) {
      reasons.add("checkpoint cycles below minimum");
    }
    if (report.getBackupRestoreCycles()
        < criteria.getMinBackupRestoreCycles()) {
      reasons.add("backup/restore cycles below minimum");
    }
    if (report.getGcCycles() < criteria.getMinGcCycles()) {
      reasons.add("gc cycles below minimum");
    }
  }

  private static void checkFaultCoverage(AdbLongRunStressReport report,
      AdbLongRunAcceptanceCriteria criteria, List<String> reasons) {
    Map<AdbFaultInjectionType, AdbFaultInjectionResult> byType =
        new EnumMap<>(AdbFaultInjectionType.class);
    for (AdbFaultInjectionResult result : report.getFaultResults()) {
      byType.put(result.getType(), result);
      if (!result.isFullyRecovered()) {
        reasons.add("fault not fully recovered: " + result.getType());
      }
    }
    for (AdbFaultInjectionType required : criteria.getRequiredFaultTypes()) {
      if (!byType.containsKey(required)) {
        reasons.add("missing fault scenario: " + required);
      }
    }
  }
}
