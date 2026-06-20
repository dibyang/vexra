package net.xdob.vexra.adb.db;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * ADB 试生产准入门禁。
 *
 * <p>release gate 证明构建和端到端验证满足发布底线；该门禁继续检查试生产特有的人工
 * 准入项，避免没有回滚、告警、值守或限制确认时把系统交给真实业务流量。</p>
 */
public final class AdbTrialProductionAdmissionGate {
  /**
   * 评估试生产准入报告。
   *
   * @param report 试生产准入报告
   * @return 通过/失败及失败原因
   */
  public AdbLongRunStressEvaluation evaluate(
      AdbTrialProductionAdmissionReport report) {
    Objects.requireNonNull(report, "report == null");
    List<String> reasons = new ArrayList<>();
    if (!report.isReleaseGatePassed()) {
      reasons.add("release gate did not pass");
    }
    if (!report.isDataScaleAccepted()) {
      reasons.add("trial data scale is not accepted");
    }
    if (!report.isRollbackPlanReady()) {
      reasons.add("rollback plan is not ready");
    }
    if (!report.isAlertingReady()) {
      reasons.add("alerting is not ready");
    }
    if (!report.isOnCallWindowReady()) {
      reasons.add("on-call window is not ready");
    }
    if (!report.isKnownLimitationsAccepted()) {
      reasons.add("known limitations are not accepted");
    }
    return new AdbLongRunStressEvaluation(reasons);
  }
}
