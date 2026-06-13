package net.xdob.vexra.adb.db;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ADB 长稳报告评估结果。
 *
 * <p>结果包含是否通过和失败原因列表，可直接作为发布 gate 的输出。</p>
 */
public final class AdbLongRunStressEvaluation {
  private final boolean passed;
  private final List<String> failureReasons;

  AdbLongRunStressEvaluation(List<String> failureReasons) {
    this.failureReasons = Collections.unmodifiableList(
        new ArrayList<>(failureReasons));
    this.passed = failureReasons.isEmpty();
  }

  public boolean isPassed() {
    return passed;
  }

  public List<String> getFailureReasons() {
    return failureReasons;
  }
}
