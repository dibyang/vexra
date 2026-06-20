package net.xdob.vexra.adb.db;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ADB 进程级恢复演练发布门禁。
 *
 * <p>该门禁验证 kill/restart 演练报告是否覆盖 GA-02 的最小进程级故障集合，并要求每个
 * 场景都真实执行、恢复成功且数据 checksum 一致。它不直接控制进程，后续 release profile
 * 可以把真实演练结果转换成本模型。</p>
 */
public final class AdbRecoveryDrillGate {

  /**
   * 评估进程级恢复演练报告。
   *
   * @param report 恢复演练报告
   * @return 通过状态和失败原因
   */
  public AdbLongRunStressEvaluation evaluate(AdbRecoveryDrillReport report) {
    Objects.requireNonNull(report, "report == null");
    List<String> reasons = new ArrayList<>();
    Map<AdbRecoveryDrillScenario, AdbRecoveryDrillResult> byScenario =
        new EnumMap<>(AdbRecoveryDrillScenario.class);
    for (AdbRecoveryDrillResult result : report.getResults()) {
      byScenario.put(result.getScenario(), result);
      checkResult(result, reasons);
    }
    for (AdbRecoveryDrillScenario required
        : EnumSet.allOf(AdbRecoveryDrillScenario.class)) {
      if (!byScenario.containsKey(required)) {
        reasons.add("missing recovery drill scenario: " + required);
      }
    }
    return new AdbLongRunStressEvaluation(reasons);
  }

  private static void checkResult(AdbRecoveryDrillResult result,
      List<String> reasons) {
    if (!result.isAttempted()) {
      reasons.add("recovery drill not attempted: " + result.getScenario());
    }
    if (!result.isRecovered()) {
      reasons.add("recovery drill not recovered: " + result.getScenario());
    }
    if (!result.isChecksumMatched()) {
      reasons.add("recovery drill checksum mismatch: "
          + result.getScenario());
    }
  }
}
