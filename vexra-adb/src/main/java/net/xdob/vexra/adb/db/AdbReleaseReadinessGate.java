package net.xdob.vexra.adb.db;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * ADB GA-07 发布就绪门禁。
 *
 * <p>该门禁位于所有阶段门禁之后，用于把 GA-01 到 GA-06 的阶段证据、端到端 release
 * profile、试生产准入、evidence 归档和文档同步收敛为最终发布判断。它只校验汇总证据，
 * 不重新执行底层测试或运维命令。</p>
 */
public final class AdbReleaseReadinessGate {

  /**
   * 评估 GA-07 发布就绪报告。
   *
   * @param report 发布就绪结构化报告
   * @return 通过状态和失败原因
   */
  public AdbLongRunStressEvaluation evaluate(
      AdbReleaseReadinessReport report) {
    Objects.requireNonNull(report, "report == null");
    List<String> reasons = new ArrayList<>();
    if (!report.isProductionScopeFrozen()) {
      reasons.add("production scope is not frozen");
    }
    if (!report.isJdbcCompatibilityValidated()) {
      reasons.add("JDBC compatibility was not validated");
    }
    if (!report.isDataSafetyGatePassed()) {
      reasons.add("data safety gate did not pass");
    }
    if (!report.isControlPlaneGatePassed()) {
      reasons.add("control-plane gate did not pass");
    }
    if (!report.isTransactionGatePassed()) {
      reasons.add("transaction gate did not pass");
    }
    if (!report.isOperationsGatePassed()) {
      reasons.add("operations gate did not pass");
    }
    if (!report.isObservabilityGatePassed()) {
      reasons.add("observability gate did not pass");
    }
    if (!report.isEndToEndReleaseProfilePassed()) {
      reasons.add("end-to-end release profile did not pass");
    }
    if (!report.isTrialProductionAdmissionPassed()) {
      reasons.add("trial production admission did not pass");
    }
    if (!report.isReleaseEvidenceArchived()) {
      reasons.add("release evidence was not archived");
    }
    if (!report.isDocumentationUpdated()) {
      reasons.add("production documentation is not updated");
    }
    return new AdbLongRunStressEvaluation(reasons);
  }
}
