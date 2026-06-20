package net.xdob.vexra.adb.db;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ADB commit 崩溃注入发布门禁。
 *
 * <p>该门禁不负责 kill 进程或重放事务，只验证故障注入平台提交的结构化证据是否覆盖 GA-02
 * 的全部 commit 崩溃点，并且每个点都恢复到期望 marker 状态。它的输出复用长稳门禁结果，
 * 方便接入 `AdbEndToEndClusterStressGate`。</p>
 */
public final class AdbCommitCrashInjectionGate {

  /**
   * 评估 commit 崩溃注入矩阵报告。
   *
   * @param report commit 崩溃注入矩阵报告
   * @return 通过状态和失败原因
   */
  public AdbLongRunStressEvaluation evaluate(
      AdbCommitCrashInjectionReport report) {
    Objects.requireNonNull(report, "report == null");
    List<String> reasons = new ArrayList<>();
    Map<AdbCommitCrashInjectionPoint, AdbCommitCrashInjectionResult> byPoint =
        new EnumMap<>(AdbCommitCrashInjectionPoint.class);
    for (AdbCommitCrashInjectionResult result : report.getResults()) {
      byPoint.put(result.getInjectionPoint(), result);
      checkResult(result, reasons);
    }
    for (AdbCommitCrashInjectionPoint required
        : EnumSet.allOf(AdbCommitCrashInjectionPoint.class)) {
      if (!byPoint.containsKey(required)) {
        reasons.add("missing commit crash scenario: " + required);
      }
    }
    return new AdbLongRunStressEvaluation(reasons);
  }

  private static void checkResult(AdbCommitCrashInjectionResult result,
      List<String> reasons) {
    if (!result.isRecovered()) {
      reasons.add("commit crash not recovered: "
          + result.getInjectionPoint());
    }
    AdbDurableCommitState expected =
        result.getInjectionPoint().getExpectedFinalState();
    if (expected != result.getFinalState()) {
      reasons.add("commit crash final state mismatch: "
          + result.getInjectionPoint() + " expected " + expected
          + " actual " + result.getFinalState());
    }
  }
}
