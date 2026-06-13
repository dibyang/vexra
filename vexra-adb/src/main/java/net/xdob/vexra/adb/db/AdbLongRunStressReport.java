package net.xdob.vexra.adb.db;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * ADB 长稳压测报告。
 *
 * <p>报告是 ADB-Prod-06 的发布证据载体：它记录 workload、运行时长、操作量、
 * 失败率、延迟、维护循环和故障注入结果。外部长稳平台和本地短跑验收都应生成同一
 * 结构，方便 release gate 复用。</p>
 */
public final class AdbLongRunStressReport {
  private final String workloadName;
  private final long durationMillis;
  private final long totalOperations;
  private final long failedOperations;
  private final double throughputPerSecond;
  private final long p95LatencyMillis;
  private final long p99LatencyMillis;
  private final int checkpointCycles;
  private final int backupRestoreCycles;
  private final int gcCycles;
  private final List<AdbFaultInjectionResult> faultResults;

  /**
   * 创建长稳压测报告。
   *
   * @param workloadName workload 名称
   * @param durationMillis 运行时长
   * @param totalOperations 总操作数
   * @param failedOperations 失败操作数
   * @param throughputPerSecond 每秒吞吐
   * @param p95LatencyMillis P95 延迟
   * @param p99LatencyMillis P99 延迟
   * @param checkpointCycles checkpoint 循环次数
   * @param backupRestoreCycles backup/restore 循环次数
   * @param gcCycles GC 循环次数
   * @param faultResults 故障注入结果
   */
  public AdbLongRunStressReport(String workloadName, long durationMillis,
      long totalOperations, long failedOperations, double throughputPerSecond,
      long p95LatencyMillis, long p99LatencyMillis, int checkpointCycles,
      int backupRestoreCycles, int gcCycles,
      List<AdbFaultInjectionResult> faultResults) {
    this.workloadName = normalize(workloadName, "workloadName");
    this.durationMillis = nonNegative(durationMillis, "durationMillis");
    this.totalOperations = nonNegative(totalOperations, "totalOperations");
    this.failedOperations = nonNegative(failedOperations, "failedOperations");
    if (failedOperations > totalOperations) {
      throw new IllegalArgumentException(
          "failedOperations exceeds totalOperations");
    }
    if (throughputPerSecond < 0) {
      throw new IllegalArgumentException("throughputPerSecond is negative");
    }
    this.throughputPerSecond = throughputPerSecond;
    this.p95LatencyMillis = nonNegative(p95LatencyMillis,
        "p95LatencyMillis");
    this.p99LatencyMillis = nonNegative(p99LatencyMillis,
        "p99LatencyMillis");
    this.checkpointCycles = nonNegative(checkpointCycles,
        "checkpointCycles");
    this.backupRestoreCycles = nonNegative(backupRestoreCycles,
        "backupRestoreCycles");
    this.gcCycles = nonNegative(gcCycles, "gcCycles");
    this.faultResults = immutableFaultResults(faultResults);
  }

  public String getWorkloadName() {
    return workloadName;
  }

  public long getDurationMillis() {
    return durationMillis;
  }

  public long getTotalOperations() {
    return totalOperations;
  }

  public long getFailedOperations() {
    return failedOperations;
  }

  public double getThroughputPerSecond() {
    return throughputPerSecond;
  }

  public long getP95LatencyMillis() {
    return p95LatencyMillis;
  }

  public long getP99LatencyMillis() {
    return p99LatencyMillis;
  }

  public int getCheckpointCycles() {
    return checkpointCycles;
  }

  public int getBackupRestoreCycles() {
    return backupRestoreCycles;
  }

  public int getGcCycles() {
    return gcCycles;
  }

  public List<AdbFaultInjectionResult> getFaultResults() {
    return faultResults;
  }

  /**
   * 计算失败率。
   *
   * @return failedOperations / totalOperations；无操作时返回 1
   */
  public double failureRate() {
    if (totalOperations == 0) {
      return 1D;
    }
    return (double) failedOperations / (double) totalOperations;
  }

  private static List<AdbFaultInjectionResult> immutableFaultResults(
      List<AdbFaultInjectionResult> results) {
    Objects.requireNonNull(results, "faultResults == null");
    List<AdbFaultInjectionResult> copy = new ArrayList<>();
    for (AdbFaultInjectionResult result : results) {
      copy.add(Objects.requireNonNull(result, "faultResult is null"));
    }
    return Collections.unmodifiableList(copy);
  }

  private static long nonNegative(long value, String fieldName) {
    if (value < 0) {
      throw new IllegalArgumentException(fieldName + " is negative");
    }
    return value;
  }

  private static int nonNegative(int value, String fieldName) {
    if (value < 0) {
      throw new IllegalArgumentException(fieldName + " is negative");
    }
    return value;
  }

  private static String normalize(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return value.trim();
  }
}
