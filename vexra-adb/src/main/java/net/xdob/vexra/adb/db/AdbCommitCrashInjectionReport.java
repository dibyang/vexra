package net.xdob.vexra.adb.db;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * ADB commit 崩溃注入矩阵报告。
 *
 * <p>报告是 GA-02 数据安全闭环的结构化证据：它要求外部故障注入执行器把每个 commit
 * 关键崩溃点的恢复结果收集到同一个对象中，再交给 `AdbCommitCrashInjectionGate`
 * 判定是否允许进入 release。</p>
 */
public final class AdbCommitCrashInjectionReport {
  private final String scenarioName;
  private final List<AdbCommitCrashInjectionResult> results;

  /**
   * 创建 commit 崩溃注入矩阵报告。
   *
   * @param scenarioName 场景名称或执行批次名称
   * @param results 每个注入点的恢复结果
   */
  public AdbCommitCrashInjectionReport(String scenarioName,
      List<AdbCommitCrashInjectionResult> results) {
    this.scenarioName = normalize(scenarioName, "scenarioName");
    this.results = immutableResults(results);
  }

  public String getScenarioName() {
    return scenarioName;
  }

  public List<AdbCommitCrashInjectionResult> getResults() {
    return results;
  }

  private static List<AdbCommitCrashInjectionResult> immutableResults(
      List<AdbCommitCrashInjectionResult> values) {
    Objects.requireNonNull(values, "results == null");
    List<AdbCommitCrashInjectionResult> copy = new ArrayList<>();
    for (AdbCommitCrashInjectionResult value : values) {
      copy.add(Objects.requireNonNull(value, "result is null"));
    }
    return Collections.unmodifiableList(copy);
  }

  private static String normalize(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return value.trim();
  }
}
