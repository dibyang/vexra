package net.xdob.vexra.adb.db;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ADB SQL 诊断快照。
 *
 * <p>快照由 {@link AdbSqlDiagnosticRecorder} 生成，包含累计 SQL 数、慢 SQL 数、
 * 失败 SQL 数以及最近慢 SQL/失败 SQL 摘要。它可以直接转换为 diagnostic bundle
 * 的 operations 和 metrics 字段。</p>
 */
public final class AdbSqlDiagnosticSnapshot {
  private final long totalSqlCount;
  private final long slowSqlCount;
  private final long failedSqlCount;
  private final long maxLatencyMillis;
  private final List<AdbSqlDiagnosticEvent> recentSlowSql;
  private final List<AdbSqlDiagnosticEvent> recentFailedSql;
  private final Map<String, AdbSqlOperationStats> operationStats;

  /**
   * 创建 SQL 诊断快照。
   */
  public AdbSqlDiagnosticSnapshot(long totalSqlCount, long slowSqlCount,
      long failedSqlCount, long maxLatencyMillis,
      List<AdbSqlDiagnosticEvent> recentSlowSql,
      List<AdbSqlDiagnosticEvent> recentFailedSql,
      Map<String, AdbSqlOperationStats> operationStats) {
    this.totalSqlCount = nonNegative(totalSqlCount, "totalSqlCount");
    this.slowSqlCount = nonNegative(slowSqlCount, "slowSqlCount");
    this.failedSqlCount = nonNegative(failedSqlCount, "failedSqlCount");
    this.maxLatencyMillis = nonNegative(maxLatencyMillis, "maxLatencyMillis");
    this.recentSlowSql = immutableEvents(recentSlowSql, "recentSlowSql");
    this.recentFailedSql = immutableEvents(recentFailedSql,
        "recentFailedSql");
    this.operationStats = immutableOperationStats(operationStats);
  }

  public long getTotalSqlCount() {
    return totalSqlCount;
  }

  public long getSlowSqlCount() {
    return slowSqlCount;
  }

  public long getFailedSqlCount() {
    return failedSqlCount;
  }

  public long getMaxLatencyMillis() {
    return maxLatencyMillis;
  }

  public List<AdbSqlDiagnosticEvent> getRecentSlowSql() {
    return recentSlowSql;
  }

  public List<AdbSqlDiagnosticEvent> getRecentFailedSql() {
    return recentFailedSql;
  }

  public Map<String, AdbSqlOperationStats> getOperationStats() {
    return operationStats;
  }

  /**
   * 转换为 diagnostic bundle operations 字段。
   */
  public Map<String, String> toOperations(String prefix) {
    String normalized = normalizePrefix(prefix);
    Map<String, String> values = new LinkedHashMap<>();
    values.put(normalized + ".totalSqlCount", String.valueOf(totalSqlCount));
    values.put(normalized + ".slowSqlCount", String.valueOf(slowSqlCount));
    values.put(normalized + ".failedSqlCount", String.valueOf(failedSqlCount));
    values.put(normalized + ".maxLatencyMillis",
        String.valueOf(maxLatencyMillis));
    putEvents(values, normalized + ".recentSlowSql", recentSlowSql);
    putEvents(values, normalized + ".recentFailedSql", recentFailedSql);
    putOperationStats(values, normalized + ".operationStats",
        operationStats);
    return values;
  }

  /**
   * 转换为 diagnostic bundle metrics 字段。
   */
  public Map<String, Number> toMetrics(String prefix) {
    String normalized = normalizePrefix(prefix);
    Map<String, Number> values = new LinkedHashMap<>();
    values.put(normalized + "_total_sql_count", totalSqlCount);
    values.put(normalized + "_slow_sql_count", slowSqlCount);
    values.put(normalized + "_failed_sql_count", failedSqlCount);
    values.put(normalized + "_max_latency_millis", maxLatencyMillis);
    for (AdbSqlOperationStats stats : operationStats.values()) {
      String operation = sanitizeMetricName(stats.getOperation());
      values.put(normalized + "_operation_" + operation + "_count",
          stats.getCount());
      values.put(normalized + "_operation_" + operation
          + "_avg_latency_micros", stats.getAverageLatencyMicros());
      values.put(normalized + "_operation_" + operation
          + "_max_latency_millis", stats.getMaxLatencyMillis());
    }
    return values;
  }

  private static void putOperationStats(Map<String, String> target,
      String prefix, Map<String, AdbSqlOperationStats> stats) {
    target.put(prefix + ".count", String.valueOf(stats.size()));
    int index = 0;
    for (AdbSqlOperationStats item : stats.values()) {
      String itemPrefix = prefix + "." + index;
      target.put(itemPrefix + ".operation", item.getOperation());
      target.put(itemPrefix + ".count", String.valueOf(item.getCount()));
      target.put(itemPrefix + ".failedCount",
          String.valueOf(item.getFailedCount()));
      target.put(itemPrefix + ".totalLatencyMillis",
          String.valueOf(item.getTotalLatencyMillis()));
      target.put(itemPrefix + ".avgLatencyMicros",
          String.valueOf(item.getAverageLatencyMicros()));
      target.put(itemPrefix + ".maxLatencyMillis",
          String.valueOf(item.getMaxLatencyMillis()));
      index++;
    }
  }

  private static void putEvents(Map<String, String> target, String prefix,
      List<AdbSqlDiagnosticEvent> events) {
    target.put(prefix + ".count", String.valueOf(events.size()));
    for (int i = 0; i < events.size(); i++) {
      target.put(prefix + "." + i, events.get(i).renderSummary());
    }
  }

  private static List<AdbSqlDiagnosticEvent> immutableEvents(
      List<AdbSqlDiagnosticEvent> source, String fieldName) {
    Objects.requireNonNull(source, fieldName + " == null");
    return Collections.unmodifiableList(new ArrayList<>(source));
  }

  private static Map<String, AdbSqlOperationStats> immutableOperationStats(
      Map<String, AdbSqlOperationStats> source) {
    Objects.requireNonNull(source, "operationStats == null");
    return Collections.unmodifiableMap(new LinkedHashMap<>(source));
  }

  private static long nonNegative(long value, String name) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " is negative: " + value);
    }
    return value;
  }

  private static String normalizePrefix(String prefix) {
    String text = prefix == null ? "" : prefix.trim();
    return text.isEmpty() ? "sql" : text;
  }

  private static String sanitizeMetricName(String value) {
    String text = value == null ? "unknown" : value.trim().toLowerCase();
    StringBuilder builder = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
        builder.append(c);
      } else {
        builder.append('_');
      }
    }
    return builder.length() == 0 ? "unknown" : builder.toString();
  }
}
