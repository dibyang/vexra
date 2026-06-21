package net.xdob.vexra.adb.db;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADB SQL 诊断记录器测试。
 *
 * <p>测试覆盖慢 SQL、失败 SQL、最近事件窗口和 diagnostic bundle 字段转换，
 * 为后续 h2db 插件监听或 JDBC hook 接入提供稳定契约。</p>
 */
class AdbSqlDiagnosticRecorderTest {
  /**
   * 验证慢 SQL 和失败 SQL 会进入诊断快照。
   */
  @Test
  void shouldRecordSlowAndFailedSqlSummary() {
    AdbSqlDiagnosticRecorder recorder = new AdbSqlDiagnosticRecorder(100, 2);

    recorder.record(AdbSqlDiagnosticEvent.success(1, "SELECT", "T",
        "select * from T", 20));
    recorder.record(AdbSqlDiagnosticEvent.success(2, "SELECT", "T",
        "select * from T where id = 1", 120));
    recorder.record(AdbSqlDiagnosticEvent.failure(3, "INSERT", "T",
        "insert into T values(1)", 150,
        new SQLException("duplicate key", "23505")));
    recorder.record(AdbSqlDiagnosticEvent.failure(4, "UPDATE", "T",
        "update T set name='x'", 30,
        new SQLException("region unavailable", "ADB01")));

    AdbSqlDiagnosticSnapshot snapshot = recorder.snapshot();

    assertEquals(4, snapshot.getTotalSqlCount());
    assertEquals(2, snapshot.getSlowSqlCount());
    assertEquals(2, snapshot.getFailedSqlCount());
    assertEquals(150, snapshot.getMaxLatencyMillis());
    assertEquals(2, snapshot.getRecentSlowSql().size());
    assertEquals(2, snapshot.getRecentFailedSql().size());
    assertEquals(4, snapshot.getOperationStats().size());
    assertEquals(1, snapshot.getOperationStats().get("select * from T")
        .getCount());
    assertEquals(20_000, snapshot.getOperationStats().get("select * from T")
        .getAverageLatencyMicros());
    assertTrue(snapshot.getRecentFailedSql().get(0).renderSummary()
        .contains("duplicate key"));
  }

  /**
   * 验证最近事件窗口只保留末尾事件。
   */
  @Test
  void shouldKeepBoundedRecentEvents() {
    AdbSqlDiagnosticRecorder recorder = new AdbSqlDiagnosticRecorder(10, 1);

    recorder.record(AdbSqlDiagnosticEvent.success(1, "SELECT", "T",
        "select 1", 20));
    recorder.record(AdbSqlDiagnosticEvent.success(2, "SELECT", "T",
        "select 2", 30));

    AdbSqlDiagnosticSnapshot snapshot = recorder.snapshot();

    assertEquals(2, snapshot.getSlowSqlCount());
    assertEquals(1, snapshot.getRecentSlowSql().size());
    assertTrue(snapshot.getRecentSlowSql().get(0).renderSummary()
        .contains("select 2"));
  }

  /**
   * 验证快照可以转换为诊断包 operations 和 metrics 字段。
   */
  @Test
  void shouldExportBundleFields() {
    AdbSqlDiagnosticRecorder recorder = new AdbSqlDiagnosticRecorder(50, 3);
    recorder.record(AdbSqlDiagnosticEvent.failure(1, "DELETE", "T",
        "delete from T", 60, new SQLException("write conflict", "40001")));

    AdbSqlDiagnosticSnapshot snapshot = recorder.snapshot();
    Map<String, String> operations = snapshot.toOperations("sql");
    Map<String, Number> metrics = snapshot.toMetrics("adb_sql");

    assertEquals("1", operations.get("sql.totalSqlCount"));
    assertEquals("1", operations.get("sql.recentSlowSql.count"));
    assertTrue(operations.get("sql.recentFailedSql.0")
        .contains("write conflict"));
    assertEquals(1L, metrics.get("adb_sql_total_sql_count"));
    assertEquals(1L, metrics.get("adb_sql_failed_sql_count"));
    assertEquals("1", operations.get("sql.operationStats.count"));
    assertEquals("delete from T",
        operations.get("sql.operationStats.0.operation"));
    assertEquals(1L,
        metrics.get("adb_sql_operation_delete_from_t_count"));
  }
}
