package net.xdob.vexra.adb.db;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADB commit 崩溃注入门禁测试。
 *
 * <p>测试覆盖 GA-02 数据安全闭环要求的全部 commit 崩溃点：缺失任一场景、恢复失败或最终
 * marker 状态不符合语义时都不能进入 release gate。</p>
 */
class AdbCommitCrashInjectionGateTest {

  /**
   * 验证完整 commit 崩溃注入矩阵可以通过门禁。
   */
  @Test
  void shouldPassCompleteCrashInjectionMatrix() {
    AdbLongRunStressEvaluation evaluation =
        new AdbCommitCrashInjectionGate().evaluate(completeReport());

    assertTrue(evaluation.isPassed());
  }

  /**
   * 验证缺失必需注入点时不能通过门禁。
   */
  @Test
  void shouldFailWhenScenarioIsMissing() {
    AdbCommitCrashInjectionReport report =
        new AdbCommitCrashInjectionReport("missing", Collections.singletonList(
            recovered(AdbCommitCrashInjectionPoint.BEFORE_PREWRITE, null)));

    AdbLongRunStressEvaluation evaluation =
        new AdbCommitCrashInjectionGate().evaluate(report);

    assertFalse(evaluation.isPassed());
    assertTrue(evaluation.getFailureReasons().contains(
        "missing commit crash scenario: AFTER_PREWRITE_BEFORE_RAFT"));
  }

  /**
   * 验证注入点未恢复时不能通过门禁。
   */
  @Test
  void shouldFailWhenScenarioDoesNotRecover() {
    AdbCommitCrashInjectionReport report =
        new AdbCommitCrashInjectionReport("not-recovered", Arrays.asList(
            recovered(AdbCommitCrashInjectionPoint.BEFORE_PREWRITE, null),
            failed(AdbCommitCrashInjectionPoint.AFTER_PREWRITE_BEFORE_RAFT,
                AdbDurableCommitState.ROLLED_BACK),
            recovered(AdbCommitCrashInjectionPoint.AFTER_RAFT_BEFORE_STORE,
                AdbDurableCommitState.STORE_COMMITTED),
            recovered(AdbCommitCrashInjectionPoint.AFTER_STORE_BEFORE_REPLY,
                AdbDurableCommitState.REPLIED)));

    AdbLongRunStressEvaluation evaluation =
        new AdbCommitCrashInjectionGate().evaluate(report);

    assertFalse(evaluation.isPassed());
    assertTrue(evaluation.getFailureReasons().contains(
        "commit crash not recovered: AFTER_PREWRITE_BEFORE_RAFT"));
  }

  /**
   * 验证恢复后的 marker 终态不匹配时不能通过门禁。
   */
  @Test
  void shouldFailWhenFinalStateDoesNotMatchSemantics() {
    AdbCommitCrashInjectionReport report =
        new AdbCommitCrashInjectionReport("bad-state", Arrays.asList(
            recovered(AdbCommitCrashInjectionPoint.BEFORE_PREWRITE, null),
            recovered(AdbCommitCrashInjectionPoint.AFTER_PREWRITE_BEFORE_RAFT,
                AdbDurableCommitState.PREWRITTEN),
            recovered(AdbCommitCrashInjectionPoint.AFTER_RAFT_BEFORE_STORE,
                AdbDurableCommitState.STORE_COMMITTED),
            recovered(AdbCommitCrashInjectionPoint.AFTER_STORE_BEFORE_REPLY,
                AdbDurableCommitState.REPLIED)));

    AdbLongRunStressEvaluation evaluation =
        new AdbCommitCrashInjectionGate().evaluate(report);

    assertFalse(evaluation.isPassed());
    assertTrue(evaluation.getFailureReasons().contains(
        "commit crash final state mismatch: AFTER_PREWRITE_BEFORE_RAFT"
            + " expected ROLLED_BACK actual PREWRITTEN"));
  }

  static AdbCommitCrashInjectionReport completeReport() {
    return new AdbCommitCrashInjectionReport("complete", Arrays.asList(
        recovered(AdbCommitCrashInjectionPoint.BEFORE_PREWRITE, null),
        recovered(AdbCommitCrashInjectionPoint.AFTER_PREWRITE_BEFORE_RAFT,
            AdbDurableCommitState.ROLLED_BACK),
        recovered(AdbCommitCrashInjectionPoint.AFTER_RAFT_BEFORE_STORE,
            AdbDurableCommitState.STORE_COMMITTED),
        recovered(AdbCommitCrashInjectionPoint.AFTER_STORE_BEFORE_REPLY,
            AdbDurableCommitState.REPLIED)));
  }

  private static AdbCommitCrashInjectionResult recovered(
      AdbCommitCrashInjectionPoint point, AdbDurableCommitState finalState) {
    return new AdbCommitCrashInjectionResult(point, true, finalState,
        "recovered");
  }

  private static AdbCommitCrashInjectionResult failed(
      AdbCommitCrashInjectionPoint point, AdbDurableCommitState finalState) {
    return new AdbCommitCrashInjectionResult(point, false, finalState,
        "not recovered");
  }
}
