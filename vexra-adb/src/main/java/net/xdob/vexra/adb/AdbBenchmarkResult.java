package net.xdob.vexra.adb;

import java.util.Properties;

/**
 * ADB benchmark 执行结果。
 *
 * <p>该对象承载一次 JDBC benchmark 的稳定输出字段，供命令行、Gradle 任务和后续
 * release evidence 复用。结果只记录聚合指标，不包含业务数据或敏感连接凭据。</p>
 */
public final class AdbBenchmarkResult {
  private final String mode;
  private final String workload;
  private final String url;
  private final long warmupOperations;
  private final long operations;
  private final long failedOperations;
  private final long durationMillis;
  private final double throughputPerSecond;
  private final long p50LatencyMicros;
  private final long p95LatencyMicros;
  private final long p99LatencyMicros;
  private final long maxLatencyMicros;

  /**
   * 创建 benchmark 结果。
   *
   * @param mode benchmark 模式：jdbc 或 store
   * @param workload workload 名称
   * @param url JDBC URL 或 store 目录
   * @param warmupOperations 预热操作数
   * @param operations 正式统计操作数
   * @param failedOperations 失败操作数
   * @param durationMillis 正式统计耗时
   * @param throughputPerSecond 每秒吞吐
   * @param p50LatencyMicros P50 延迟，微秒
   * @param p95LatencyMicros P95 延迟，微秒
   * @param p99LatencyMicros P99 延迟，微秒
   * @param maxLatencyMicros 最大延迟，微秒
   */
  public AdbBenchmarkResult(String mode, String workload, String url,
      long warmupOperations, long operations, long failedOperations,
      long durationMillis, double throughputPerSecond,
      long p50LatencyMicros, long p95LatencyMicros, long p99LatencyMicros,
      long maxLatencyMicros) {
    this.mode = requireText(mode, "mode");
    this.workload = requireText(workload, "workload");
    this.url = requireText(url, "url");
    this.warmupOperations = nonNegative(warmupOperations,
        "warmupOperations");
    this.operations = nonNegative(operations, "operations");
    this.failedOperations = nonNegative(failedOperations,
        "failedOperations");
    if (failedOperations > operations) {
      throw new IllegalArgumentException(
          "failedOperations exceeds operations");
    }
    this.durationMillis = nonNegative(durationMillis, "durationMillis");
    if (throughputPerSecond < 0D) {
      throw new IllegalArgumentException("throughputPerSecond is negative");
    }
    this.throughputPerSecond = throughputPerSecond;
    this.p50LatencyMicros = nonNegative(p50LatencyMicros,
        "p50LatencyMicros");
    this.p95LatencyMicros = nonNegative(p95LatencyMicros,
        "p95LatencyMicros");
    this.p99LatencyMicros = nonNegative(p99LatencyMicros,
        "p99LatencyMicros");
    this.maxLatencyMicros = nonNegative(maxLatencyMicros,
        "maxLatencyMicros");
  }

  public String getMode() {
    return mode;
  }

  public String getWorkload() {
    return workload;
  }

  public String getUrl() {
    return url;
  }

  public long getWarmupOperations() {
    return warmupOperations;
  }

  public long getOperations() {
    return operations;
  }

  public long getFailedOperations() {
    return failedOperations;
  }

  public long getDurationMillis() {
    return durationMillis;
  }

  public double getThroughputPerSecond() {
    return throughputPerSecond;
  }

  public long getP50LatencyMicros() {
    return p50LatencyMicros;
  }

  public long getP95LatencyMicros() {
    return p95LatencyMicros;
  }

  public long getP99LatencyMicros() {
    return p99LatencyMicros;
  }

  public long getMaxLatencyMicros() {
    return maxLatencyMicros;
  }

  /**
   * 转换为 properties 输出。
   *
   * @return 可直接写入文件的 properties
   */
  public Properties toProperties() {
    Properties properties = new Properties();
    properties.setProperty("mode", mode);
    properties.setProperty("workload", workload);
    properties.setProperty("url", url);
    properties.setProperty("warmupOperations",
        String.valueOf(warmupOperations));
    properties.setProperty("operations", String.valueOf(operations));
    properties.setProperty("failedOperations",
        String.valueOf(failedOperations));
    properties.setProperty("durationMillis", String.valueOf(durationMillis));
    properties.setProperty("throughputPerSecond",
        String.valueOf(throughputPerSecond));
    properties.setProperty("p50LatencyMicros",
        String.valueOf(p50LatencyMicros));
    properties.setProperty("p95LatencyMicros",
        String.valueOf(p95LatencyMicros));
    properties.setProperty("p99LatencyMicros",
        String.valueOf(p99LatencyMicros));
    properties.setProperty("maxLatencyMicros",
        String.valueOf(maxLatencyMicros));
    properties.setProperty("passed",
        String.valueOf(failedOperations == 0));
    return properties;
  }

  private static String requireText(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return value.trim();
  }

  private static long nonNegative(long value, String fieldName) {
    if (value < 0) {
      throw new IllegalArgumentException(fieldName + " is negative");
    }
    return value;
  }
}
