package net.xdob.vexra.adb.db;

/**
 * ADB SQL/table-engine 操作聚合统计。
 *
 * <p>该对象用于把 `AdbTable`、`AdbPrimaryIndex`、`AdbSecondaryIndex` 等入口记录的
 * 单次诊断事件汇总成稳定指标。它只保存操作名和耗时聚合，不保存 SQL 参数或结果集内容。</p>
 */
public final class AdbSqlOperationStats {
  private final String operation;
  private final long count;
  private final long failedCount;
  private final long totalLatencyMillis;
  private final long maxLatencyMillis;

  /**
   * 创建操作聚合统计。
   *
   * @param operation 操作名，通常为 `ADB_TABLE_*`
   * @param count 总次数
   * @param failedCount 失败次数
   * @param totalLatencyMillis 总耗时，毫秒
   * @param maxLatencyMillis 最大耗时，毫秒
   */
  public AdbSqlOperationStats(String operation, long count, long failedCount,
      long totalLatencyMillis, long maxLatencyMillis) {
    this.operation = requireText(operation, "operation");
    this.count = nonNegative(count, "count");
    this.failedCount = nonNegative(failedCount, "failedCount");
    if (failedCount > count) {
      throw new IllegalArgumentException("failedCount exceeds count");
    }
    this.totalLatencyMillis = nonNegative(totalLatencyMillis,
        "totalLatencyMillis");
    this.maxLatencyMillis = nonNegative(maxLatencyMillis,
        "maxLatencyMillis");
  }

  public String getOperation() {
    return operation;
  }

  public long getCount() {
    return count;
  }

  public long getFailedCount() {
    return failedCount;
  }

  public long getTotalLatencyMillis() {
    return totalLatencyMillis;
  }

  public long getMaxLatencyMillis() {
    return maxLatencyMillis;
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
    return (totalLatencyMillis * 1_000L) / count;
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
