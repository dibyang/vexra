package net.xdob.vexra.adb.db;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * ADB 端到端集群压测门禁。
 *
 * <p>门禁复用已有长稳压测 evaluator，再叠加 Run-12 对真实集群闭环的要求：SQL 到
 * region 的读写 smoke、恢复演练和滚动升级演练必须全部通过，并且至少有一次 SQL/region
 * smoke 循环。</p>
 */
public final class AdbEndToEndClusterStressGate {
  private final AdbLongRunStressEvaluator longRunEvaluator =
      new AdbLongRunStressEvaluator();

  /**
   * 评估端到端集群压测报告。
   *
   * @param report 端到端报告
   * @param criteria 长稳验收标准
   * @return 评估结果
   */
  public AdbLongRunStressEvaluation evaluate(
      AdbEndToEndClusterStressReport report,
      AdbLongRunAcceptanceCriteria criteria) {
    Objects.requireNonNull(report, "report == null");
    Objects.requireNonNull(criteria, "criteria == null");
    List<String> reasons = new ArrayList<>(
        longRunEvaluator.evaluate(report.getLongRunReport(), criteria)
            .getFailureReasons());
    if (!report.isClusterReadWritePassed()) {
      reasons.add("cluster read/write smoke failed");
    }
    if (!report.isRecoveryDrillPassed()) {
      reasons.add("recovery drill failed");
    }
    if (!report.isRollingUpgradePassed()) {
      reasons.add("rolling upgrade drill failed");
    }
    if (report.getSqlRegionSmokeCycles() <= 0) {
      reasons.add("sql/region smoke cycle is missing");
    }
    return new AdbLongRunStressEvaluation(reasons);
  }
}
