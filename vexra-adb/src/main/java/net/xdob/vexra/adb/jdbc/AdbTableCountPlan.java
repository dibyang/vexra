package net.xdob.vexra.adb.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import net.xdob.vexra.adb.db.AdbTable;
import org.h2.engine.Session;
import org.h2.engine.SessionLocal;
import org.h2.index.Index;
import org.h2.jdbc.JdbcConnection;
import org.h2.schema.Schema;
import org.h2.table.Column;
import org.h2.table.Table;

/**
 * ADB 全表 COUNT 的 JDBC 快路径计划。
 *
 * <p>当前只识别 {@code SELECT COUNT(*) FROM table}。命中后直接读取 ADB
 * row-count 元数据和当前事务本地 delta，避免进入 h2db 通用聚合执行器；其他
 * COUNT 形态继续回退到 h2db，保留完整 SQL 语义。</p>
 */
final class AdbTableCountPlan {

  private static final String SELECT_COUNT = "SELECT COUNT(*)";
  private static final String FROM = " FROM ";
  private static final String WHERE = " WHERE ";
  private static final String IS_NOT_NULL = " IS NOT NULL";

  private final String tableName;
  private final String notNullColumn;
  private AdbTable resolvedTable;
  private SessionLocal cachedSession;

  private AdbTableCountPlan(String tableName, String notNullColumn) {
    this.tableName = tableName;
    this.notNullColumn = notNullColumn;
  }

  /**
   * 解析可走全表 COUNT 快路径的 SQL。
   *
   * @param sql SQL 文本
   * @return 命中计划；不支持时返回 {@code null}
   */
  static AdbTableCountPlan parse(String sql) {
    if (sql == null) {
      return null;
    }
    String trimmed = sql.trim();
    if (trimmed.endsWith(";")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
    }
    String upper = trimmed.toUpperCase(Locale.ROOT);
    if (!upper.startsWith(SELECT_COUNT + " ")) {
      return null;
    }
    int from = upper.indexOf(FROM);
    if (from < SELECT_COUNT.length()) {
      return null;
    }
    String tablePart = trimmed.substring(from + FROM.length()).trim();
    String notNullColumn = null;
    int where = tablePart.toUpperCase(Locale.ROOT).indexOf(WHERE);
    if (where >= 0) {
      String condition = tablePart.substring(where + WHERE.length()).trim();
      tablePart = tablePart.substring(0, where).trim();
      String conditionUpper = condition.toUpperCase(Locale.ROOT);
      if (!conditionUpper.endsWith(IS_NOT_NULL)) {
        return null;
      }
      String column = condition.substring(0,
          condition.length() - IS_NOT_NULL.length()).trim();
      if (column.isEmpty() || column.indexOf(' ') >= 0) {
        return null;
      }
      notNullColumn = normalizeIdentifier(column);
    }
    if (tablePart.isEmpty() || tablePart.indexOf(' ') >= 0) {
      return null;
    }
    return new AdbTableCountPlan(normalizeIdentifier(tablePart),
        notNullColumn);
  }

  /**
   * 返回该计划需要记录的 JDBC 参数个数。
   *
   * @return 参数个数
   */
  int parameterCount() {
    return 0;
  }

  /**
   * 尝试执行全表 COUNT。
   *
   * @param connection h2db 原始连接
   * @return 命中时返回单行 ResultSet；目标表不是 ADB 表时返回 {@code null}
   * @throws SQLException 读取 row-count 元数据失败时抛出
   */
  ResultSet tryExecuteQuery(Connection connection) throws SQLException {
    SessionLocal session = resolveSession(connection);
    AdbTable table = resolveAdbTable(session);
    if (table == null) {
      return null;
    }
    if (notNullColumn != null && !isNonNullable(table, notNullColumn)) {
      return null;
    }
    long startMillis = System.currentTimeMillis();
    Throwable failure = null;
    try {
      long count = table.countRowsForTableCount(session);
      return AdbSimpleResultSet.singleLong("COUNT(*)", count);
    } catch (SQLException e) {
      failure = e;
      throw e;
    } catch (RuntimeException e) {
      failure = e;
      throw e;
    } finally {
      table.recordSqlDiagnostic("SELECT", "TABLE_COUNT_FAST", startMillis,
          failure);
    }
  }

  private AdbTable resolveAdbTable(SessionLocal session) {
    if (resolvedTable != null) {
      return resolvedTable;
    }
    AdbTable table = adbTable(session);
    if (table == null) {
      return null;
    }
    resolvedTable = table;
    return table;
  }

  private AdbTable adbTable(SessionLocal session) {
    Schema schema = session.getDatabase().getSchema(
        session.getCurrentSchemaName());
    String localTableName = tableName;
    int dot = tableName.indexOf('.');
    if (dot > 0) {
      schema = session.getDatabase().getSchema(tableName.substring(0, dot));
      localTableName = tableName.substring(dot + 1);
    }
    Table table = schema.findTableOrView(session, localTableName);
    return table instanceof AdbTable ? (AdbTable) table : null;
  }

  private static boolean isNonNullable(AdbTable table, String columnName) {
    Column column = table.getColumn(columnName, true);
    return column != null && (!column.isNullable()
        || column.isPrimaryKey() || isPrimaryKeyColumn(table, column));
  }

  private static boolean isPrimaryKeyColumn(AdbTable table, Column column) {
    Index primaryKey = table.findPrimaryKey();
    if (primaryKey == null) {
      return false;
    }
    for (Column primaryColumn : primaryKey.getColumns()) {
      if (primaryColumn == column
          || primaryColumn.getColumnId() == column.getColumnId()) {
        return true;
      }
    }
    return false;
  }

  /**
   * 返回当前 PreparedStatement 绑定连接的 H2 session。
   *
   * <p>prepared table count 计划绑定单个 JDBC statement，缓存 session 可以减少
   * 高频 COUNT 快路径上的 unwrap / 类型检查成本。</p>
   */
  private SessionLocal resolveSession(Connection connection) throws SQLException {
    if (cachedSession != null) {
      return cachedSession;
    }
    Session session = connection.unwrap(JdbcConnection.class).getSession();
    if (!(session instanceof SessionLocal)) {
      throw new SQLException("Unsupported H2 session type: "
          + session.getClass().getName());
    }
    cachedSession = (SessionLocal) session;
    return cachedSession;
  }

  private static String normalizeIdentifier(String identifier) {
    String value = identifier.trim();
    if (value.startsWith("\"") && value.endsWith("\"")
        && value.length() >= 2) {
      return value.substring(1, value.length() - 1);
    }
    return value.toUpperCase(Locale.ROOT);
  }
}
