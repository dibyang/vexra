package net.xdob.vexra.adb.db;

import java.sql.SQLException;

/**
 * ADB SQL 诊断事件。
 *
 * <p>该对象记录一次 SQL 执行的诊断摘要，供 JDBC/Server/h2db 插件监听点或测试工具
 * 上报给 {@link AdbSqlDiagnosticRecorder}。它不保存结果集内容，只保存定位慢 SQL、
 * 失败 SQL 和表/类型分布所需的最小字段。</p>
 */
public final class AdbSqlDiagnosticEvent {
  private final long timestampMillis;
  private final String sqlType;
  private final String tableName;
  private final String sql;
  private final long latencyMillis;
  private final boolean success;
  private final String errorClass;
  private final String errorMessage;

  /**
   * 创建 SQL 诊断事件。
   *
   * @param timestampMillis 事件时间
   * @param sqlType SQL 类型，例如 SELECT/INSERT/UPDATE
   * @param tableName 主要表名，未知时可传 unknown
   * @param sql SQL 文本摘要
   * @param latencyMillis 执行耗时
   * @param success 是否执行成功
   * @param errorClass 失败异常类型
   * @param errorMessage 失败摘要
   */
  public AdbSqlDiagnosticEvent(long timestampMillis, String sqlType,
      String tableName, String sql, long latencyMillis, boolean success,
      String errorClass, String errorMessage) {
    this.timestampMillis = timestampMillis;
    this.sqlType = textOrUnknown(sqlType);
    this.tableName = textOrUnknown(tableName);
    this.sql = textOrUnknown(sql);
    if (latencyMillis < 0) {
      throw new IllegalArgumentException("latencyMillis is negative: "
          + latencyMillis);
    }
    this.latencyMillis = latencyMillis;
    this.success = success;
    this.errorClass = textOrEmpty(errorClass);
    this.errorMessage = textOrEmpty(errorMessage);
  }

  /**
   * 创建成功事件。
   */
  public static AdbSqlDiagnosticEvent success(long timestampMillis,
      String sqlType, String tableName, String sql, long latencyMillis) {
    return new AdbSqlDiagnosticEvent(timestampMillis, sqlType, tableName, sql,
        latencyMillis, true, "", "");
  }

  /**
   * 创建失败事件。
   */
  public static AdbSqlDiagnosticEvent failure(long timestampMillis,
      String sqlType, String tableName, String sql, long latencyMillis,
      SQLException error) {
    String errorClass = error == null ? "SQLException"
        : error.getClass().getSimpleName();
    String errorMessage = error == null ? "" : error.getMessage();
    return new AdbSqlDiagnosticEvent(timestampMillis, sqlType, tableName, sql,
        latencyMillis, false, errorClass, errorMessage);
  }

  public long getTimestampMillis() {
    return timestampMillis;
  }

  public String getSqlType() {
    return sqlType;
  }

  public String getTableName() {
    return tableName;
  }

  public String getSql() {
    return sql;
  }

  public long getLatencyMillis() {
    return latencyMillis;
  }

  public boolean isSuccess() {
    return success;
  }

  public String getErrorClass() {
    return errorClass;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  /**
   * 渲染为诊断包中的单行摘要。
   *
   * @return key=value 风格摘要
   */
  public String renderSummary() {
    return "timestampMillis=" + timestampMillis
        + ",sqlType=" + safe(sqlType)
        + ",table=" + safe(tableName)
        + ",latencyMillis=" + latencyMillis
        + ",success=" + success
        + ",errorClass=" + safe(errorClass)
        + ",errorMessage=" + safe(errorMessage)
        + ",sql=" + safe(sql);
  }

  private static String textOrUnknown(String value) {
    String text = trimToNull(value);
    return text == null ? "unknown" : text;
  }

  private static String textOrEmpty(String value) {
    String text = trimToNull(value);
    return text == null ? "" : text;
  }

  private static String safe(String value) {
    return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ');
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
