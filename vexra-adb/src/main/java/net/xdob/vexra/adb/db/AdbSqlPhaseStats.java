package net.xdob.vexra.adb.db;

/**
 * ADB SQL/table-engine 关键阶段耗时聚合统计。
 *
 * <p>该对象只保存阶段名和耗时聚合，不保存 SQL 参数或行内容。它用于把 commit、
 * row-count、索引查找和底层写入等更细粒度阶段暴露给 benchmark 与诊断包。</p>
 */
public final class AdbSqlPhaseStats {
  private final String phase;
  private final long count;
  private final long totalLatencyMicros;
  private final long maxLatencyMicros;

  /**
   * 创建阶段耗时聚合统计。
   *
   * @param phase 阶段名
   * @param count 记录次数
   * @param totalLatencyMicros 总耗时，微秒
   * @param maxLatencyMicros 最大耗时，微秒
   */
  public AdbSqlPhaseStats(String phase, long count, long totalLatencyMicros,
      long maxLatencyMicros) {
    this.phase = requireText(phase, "phase");
    this.count = nonNegative(count, "count");
    this.totalLatencyMicros = nonNegative(totalLatencyMicros,
        "totalLatencyMicros");
    this.maxLatencyMicros = nonNegative(maxLatencyMicros,
        "maxLatencyMicros");
  }

  public String getPhase() {
    return phase;
  }

  public long getCount() {
    return count;
  }

  public long getTotalLatencyMicros() {
    return totalLatencyMicros;
  }

  public long getMaxLatencyMicros() {
    return maxLatencyMicros;
  }

  /**
   * 返回平均耗时，微秒。
   *
   * @return 平均耗时，微秒；无样本时返回 0
   */
  public long getAverageLatencyMicros() {
    if (count == 0) {
      return 0L;
    }
    return totalLatencyMicros / count;
  }

  private static String requireText(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return value.trim();
  }

  private static long nonNegative(long value, String fieldName) {
    if (value < 0) {
      throw new IllegalArgumentException(fieldName + " is negative: " + value);
    }
    return value;
  }
}
