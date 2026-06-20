package net.xdob.vexra.adb.db;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADB 进程级恢复演练门禁测试。
 *
 * <p>测试覆盖 GA-02/GA-07 对 kill leader、kill follower、kill witness 和全集群重启的
 * 最小 release gate 要求。</p>
 */
class AdbRecoveryDrillGateTest {

  /**
   * 验证完整恢复演练矩阵可以通过门禁。
   */
  @Test
  void shouldPassCompleteRecoveryDrillMatrix() {
    AdbLongRunStressEvaluation evaluation =
        new AdbRecoveryDrillGate().evaluate(completeReport());

    assertTrue(evaluation.isPassed());
  }

  /**
   * 验证缺失恢复演练场景时不能通过门禁。
   */
  @Test
  void shouldFailWhenScenarioIsMissing() {
    AdbRecoveryDrillReport report =
        new AdbRecoveryDrillReport("missing", Collections.singletonList(
            passed(AdbRecoveryDrillScenario.KILL_LEADER)));

    AdbLongRunStressEvaluation evaluation =
        new AdbRecoveryDrillGate().evaluate(report);

    assertFalse(evaluation.isPassed());
    assertTrue(evaluation.getFailureReasons().contains(
        "missing recovery drill scenario: KILL_FOLLOWER"));
  }

  /**
   * 验证演练未真实执行时不能通过门禁。
   */
  @Test
  void shouldFailWhenScenarioIsNotAttempted() {
    AdbRecoveryDrillReport report =
        new AdbRecoveryDrillReport("not-attempted", Arrays.asList(
            passed(AdbRecoveryDrillScenario.KILL_LEADER),
            result(AdbRecoveryDrillScenario.KILL_FOLLOWER, false, true, true),
            passed(AdbRecoveryDrillScenario.KILL_WITNESS),
            passed(AdbRecoveryDrillScenario.FULL_CLUSTER_RESTART)));

    AdbLongRunStressEvaluation evaluation =
        new AdbRecoveryDrillGate().evaluate(report);

    assertFalse(evaluation.isPassed());
    assertTrue(evaluation.getFailureReasons().contains(
        "recovery drill not attempted: KILL_FOLLOWER"));
  }

  /**
   * 验证演练后未恢复读写或多数派时不能通过门禁。
   */
  @Test
  void shouldFailWhenScenarioDoesNotRecover() {
    AdbRecoveryDrillReport report =
        new AdbRecoveryDrillReport("not-recovered", Arrays.asList(
            passed(AdbRecoveryDrillScenario.KILL_LEADER),
            passed(AdbRecoveryDrillScenario.KILL_FOLLOWER),
            result(AdbRecoveryDrillScenario.KILL_WITNESS, true, false, true),
            passed(AdbRecoveryDrillScenario.FULL_CLUSTER_RESTART)));

    AdbLongRunStressEvaluation evaluation =
        new AdbRecoveryDrillGate().evaluate(report);

    assertFalse(evaluation.isPassed());
    assertTrue(evaluation.getFailureReasons().contains(
        "recovery drill not recovered: KILL_WITNESS"));
  }

  /**
   * 验证演练前后 checksum 不一致时不能通过门禁。
   */
  @Test
  void shouldFailWhenChecksumDoesNotMatch() {
    AdbRecoveryDrillReport report =
        new AdbRecoveryDrillReport("checksum", Arrays.asList(
            passed(AdbRecoveryDrillScenario.KILL_LEADER),
            passed(AdbRecoveryDrillScenario.KILL_FOLLOWER),
            passed(AdbRecoveryDrillScenario.KILL_WITNESS),
            result(AdbRecoveryDrillScenario.FULL_CLUSTER_RESTART, true, true,
                false)));

    AdbLongRunStressEvaluation evaluation =
        new AdbRecoveryDrillGate().evaluate(report);

    assertFalse(evaluation.isPassed());
    assertTrue(evaluation.getFailureReasons().contains(
        "recovery drill checksum mismatch: FULL_CLUSTER_RESTART"));
  }

  static AdbRecoveryDrillReport completeReport() {
    return new AdbRecoveryDrillReport("complete", Arrays.asList(
        passed(AdbRecoveryDrillScenario.KILL_LEADER),
        passed(AdbRecoveryDrillScenario.KILL_FOLLOWER),
        passed(AdbRecoveryDrillScenario.KILL_WITNESS),
        passed(AdbRecoveryDrillScenario.FULL_CLUSTER_RESTART)));
  }

  private static AdbRecoveryDrillResult passed(
      AdbRecoveryDrillScenario scenario) {
    return result(scenario, true, true, true);
  }

  private static AdbRecoveryDrillResult result(
      AdbRecoveryDrillScenario scenario, boolean attempted,
      boolean recovered, boolean checksumMatched) {
    return new AdbRecoveryDrillResult(scenario, attempted, recovered,
        checksumMatched, "drill");
  }
}
