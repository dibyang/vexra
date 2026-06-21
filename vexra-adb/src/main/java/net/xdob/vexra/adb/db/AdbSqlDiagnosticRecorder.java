package net.xdob.vexra.adb.db;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;

/**
 * ADB SQL 诊断记录器。
 *
 * <p>记录器面向 GA-06 的最小 SQL 观测闭环：调用方在 SQL 执行完成或失败后记录一条
 * 事件，记录器维护累计计数、最大耗时、最近慢 SQL 和最近失败 SQL。类内部同步，适合
 * 多个 JDBC session 共享同一个轻量实例。</p>
 */
public final class AdbSqlDiagnosticRecorder {
  private final long slowSqlThresholdMillis;
  private final int maxRecentEvents;
  private final Deque<AdbSqlDiagnosticEvent> recentSlowSql =
      new ArrayDeque<>();
  private final Deque<AdbSqlDiagnosticEvent> recentFailedSql =
      new ArrayDeque<>();
  private long totalSqlCount;
  private long slowSqlCount;
  private long failedSqlCount;
  private long maxLatencyMillis;
  private final LinkedHashMap<String, MutableOperationStats> operationStats =
      new LinkedHashMap<>();

  /**
   * 创建 SQL 诊断记录器。
   *
   * @param slowSqlThresholdMillis 慢 SQL 阈值，耗时大于等于该值会进入慢 SQL 摘要
   * @param maxRecentEvents 每类最近事件最多保留数量
   */
  public AdbSqlDiagnosticRecorder(long slowSqlThresholdMillis,
      int maxRecentEvents) {
    if (slowSqlThresholdMillis < 0) {
      throw new IllegalArgumentException(
          "slowSqlThresholdMillis is negative: " + slowSqlThresholdMillis);
    }
    if (maxRecentEvents < 0) {
      throw new IllegalArgumentException("maxRecentEvents is negative: "
          + maxRecentEvents);
    }
    this.slowSqlThresholdMillis = slowSqlThresholdMillis;
    this.maxRecentEvents = maxRecentEvents;
  }

  /**
   * 记录一次 SQL 事件。
   *
   * @param event SQL 诊断事件
   */
  public synchronized void record(AdbSqlDiagnosticEvent event) {
    Objects.requireNonNull(event, "event == null");
    totalSqlCount++;
    maxLatencyMillis = Math.max(maxLatencyMillis, event.getLatencyMillis());
    operationStatsFor(event).record(event);
    if (event.getLatencyMillis() >= slowSqlThresholdMillis) {
      slowSqlCount++;
      appendBounded(recentSlowSql, event);
    }
    if (!event.isSuccess()) {
      failedSqlCount++;
      appendBounded(recentFailedSql, event);
    }
  }

  /**
   * 生成当前 SQL 诊断快照。
   *
   * @return 不可变快照
   */
  public synchronized AdbSqlDiagnosticSnapshot snapshot() {
    return new AdbSqlDiagnosticSnapshot(totalSqlCount, slowSqlCount,
        failedSqlCount, maxLatencyMillis, new ArrayList<>(recentSlowSql),
        new ArrayList<>(recentFailedSql), snapshotOperationStats());
  }

  /**
   * 清空累计诊断状态。
   *
   * <p>该方法面向 benchmark 和测试。它不修改 recorder 配置，也不影响持有 recorder 的
   * `TxnManager`，因此可以在预热后重置统计窗口。</p>
   */
  public synchronized void clear() {
    recentSlowSql.clear();
    recentFailedSql.clear();
    totalSqlCount = 0L;
    slowSqlCount = 0L;
    failedSqlCount = 0L;
    maxLatencyMillis = 0L;
    operationStats.clear();
  }

  private void appendBounded(Deque<AdbSqlDiagnosticEvent> target,
      AdbSqlDiagnosticEvent event) {
    if (maxRecentEvents == 0) {
      return;
    }
    if (target.size() == maxRecentEvents) {
      target.removeFirst();
    }
    target.addLast(event);
  }

  private MutableOperationStats operationStatsFor(
      AdbSqlDiagnosticEvent event) {
    String operation = event.getSql();
    MutableOperationStats stats = operationStats.get(operation);
    if (stats == null) {
      stats = new MutableOperationStats(operation);
      operationStats.put(operation, stats);
    }
    return stats;
  }

  private Map<String, AdbSqlOperationStats> snapshotOperationStats() {
    LinkedHashMap<String, AdbSqlOperationStats> snapshot =
        new LinkedHashMap<>();
    for (Map.Entry<String, MutableOperationStats> entry
        : operationStats.entrySet()) {
      snapshot.put(entry.getKey(), entry.getValue().snapshot());
    }
    return snapshot;
  }

  private static final class MutableOperationStats {
    private final String operation;
    private long count;
    private long failedCount;
    private long totalLatencyMillis;
    private long maxLatencyMillis;

    private MutableOperationStats(String operation) {
      this.operation = operation;
    }

    private void record(AdbSqlDiagnosticEvent event) {
      count++;
      if (!event.isSuccess()) {
        failedCount++;
      }
      totalLatencyMillis += event.getLatencyMillis();
      maxLatencyMillis = Math.max(maxLatencyMillis, event.getLatencyMillis());
    }

    private AdbSqlOperationStats snapshot() {
      return new AdbSqlOperationStats(operation, count, failedCount,
          totalLatencyMillis, maxLatencyMillis);
    }
  }
}
