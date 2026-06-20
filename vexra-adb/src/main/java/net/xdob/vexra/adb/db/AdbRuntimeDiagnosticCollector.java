package net.xdob.vexra.adb.db;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ADB runtime 诊断采集器。
 *
 * <p>该采集器把 {@link AdbRuntimeOperationsBridge} 暴露的 system row 和 metrics
 * 转换为诊断包可以直接写入的结构化字段。它不创建 store 或控制面连接，调用方需要把
 * 已经处于当前进程内的 bridge 传入，因此适合嵌入式 runtime、测试和后续 live doctor
 * 入口复用。</p>
 */
public final class AdbRuntimeDiagnosticCollector {
  private final AdbRuntimeOperationsBridge operationsBridge;

  /**
   * 创建 runtime 诊断采集器。
   *
   * @param operationsBridge runtime 运维桥接器
   */
  public AdbRuntimeDiagnosticCollector(
      AdbRuntimeOperationsBridge operationsBridge) {
    this.operationsBridge = Objects.requireNonNull(operationsBridge,
        "operationsBridge == null");
  }

  /**
   * 采集 runtime system row 和 metrics。
   *
   * @param ddlRunning 当前是否存在运行中的 DDL
   * @return 可直接并入 {@link AdbDiagnosticBundle} 的 runtime 诊断快照
   */
  public Snapshot collect(boolean ddlRunning) {
    Map<String, String> operations = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry
        : operationsBridge.systemTableRow(ddlRunning).entrySet()) {
      operations.put("runtime." + entry.getKey(), entry.getValue());
    }
    Map<String, Number> metrics = new LinkedHashMap<>(
        operationsBridge.metrics(ddlRunning));
    collectSqlDiagnostics(operations, metrics);
    return new Snapshot(operations, metrics);
  }

  /**
   * 合并当前进程内 SQL 诊断快照。
   *
   * <p>operations 保留按 scope 展开的最近事件，便于故障定位；metrics 只输出聚合数字，
   * 避免按数据库路径生成高基数指标。</p>
   */
  private static void collectSqlDiagnostics(Map<String, String> operations,
      Map<String, Number> metrics) {
    Map<String, AdbSqlDiagnosticSnapshot> snapshots =
        AdbSqlDiagnosticsRegistry.snapshotAll();
    operations.put("sql.scope.count", String.valueOf(snapshots.size()));

    long totalSqlCount = 0L;
    long slowSqlCount = 0L;
    long failedSqlCount = 0L;
    long maxLatencyMillis = 0L;
    int index = 0;
    for (Map.Entry<String, AdbSqlDiagnosticSnapshot> entry
        : snapshots.entrySet()) {
      String prefix = "sql.scope." + index;
      operations.put(prefix + ".name", entry.getKey());
      operations.putAll(entry.getValue().toOperations(prefix));
      totalSqlCount += entry.getValue().getTotalSqlCount();
      slowSqlCount += entry.getValue().getSlowSqlCount();
      failedSqlCount += entry.getValue().getFailedSqlCount();
      maxLatencyMillis = Math.max(maxLatencyMillis,
          entry.getValue().getMaxLatencyMillis());
      index++;
    }

    metrics.put("adb_sql_registered_scope_count", snapshots.size());
    metrics.put("adb_sql_total_sql_count", totalSqlCount);
    metrics.put("adb_sql_slow_sql_count", slowSqlCount);
    metrics.put("adb_sql_failed_sql_count", failedSqlCount);
    metrics.put("adb_sql_max_latency_millis", maxLatencyMillis);
  }

  /**
   * runtime 诊断快照。
   *
   * <p>operations 适合合并进诊断包 `[operations]` 段，metrics 适合合并进
   * `[metrics]` 段。两个 map 在构造后不可变，便于多消费者复用。</p>
   */
  public static final class Snapshot {
    private final Map<String, String> operations;
    private final Map<String, Number> metrics;

    private Snapshot(Map<String, String> operations,
        Map<String, Number> metrics) {
      this.operations = java.util.Collections.unmodifiableMap(
          new LinkedHashMap<>(Objects.requireNonNull(operations,
              "operations == null")));
      this.metrics = java.util.Collections.unmodifiableMap(
          new LinkedHashMap<>(Objects.requireNonNull(metrics,
              "metrics == null")));
    }

    public Map<String, String> getOperations() {
      return operations;
    }

    public Map<String, Number> getMetrics() {
      return metrics;
    }
  }
}
