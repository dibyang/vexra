package net.xdob.vexra.adb.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import net.xdob.vexra.adb.AdbBenchmarkMain;
import net.xdob.vexra.adb.db.AdbTable;
import net.xdob.vexra.adb.db.TxnMap2;
import net.xdob.vexra.adb.db.VersionReadSession;
import net.xdob.vexra.adb.key.RowPrefix;
import net.xdob.vexra.adb.key.TabId;
import org.h2.engine.Session;
import org.h2.engine.SessionLocal;
import org.h2.jdbc.JdbcConnection;
import org.h2.result.SearchRow;
import org.h2.schema.Schema;
import org.h2.table.Column;
import org.h2.table.Table;

/**
 * 参数化主键范围 COUNT 的 ADB 快路径计划。
 *
 * <p>当前只识别 {@code SELECT COUNT(*) FROM table WHERE pk BETWEEN ? AND ?}。
 * 命中后直接复用 ADB 当前事务快照上的 {@link TableScanCursor} 计数，避免 h2db 通用执行器、
 * Row materialization 和聚合对象开销。其他 SQL 继续回退 h2db。</p>
 */
final class AdbPreparedRangeCountPlan {

  private static final String SELECT_COUNT = "SELECT COUNT(*)";
  private static final String FROM = " FROM ";
  private static final String WHERE = " WHERE ";
  private static final String BETWEEN = " BETWEEN ";
  private static final String PARAM_AND_PARAM = "? AND ?";
  private static final String COUNT_COLUMN = "COUNT(*)";

  private final String tableName;
  private final String whereColumn;
  private AdbTable resolvedTable;
  private SessionLocal cachedSession;
  private TabId cachedTabId;
  private RowPrefix cachedRowPrefix;
  private VersionReadSession readSession;
  private long readSessionModificationId = Long.MIN_VALUE;
  private final AdbSimpleResultSet.SingleLongResultSet countResultSet =
      AdbSimpleResultSet.reusableSingleLong(COUNT_COLUMN);

  private AdbPreparedRangeCountPlan(String tableName, String whereColumn) {
    this.tableName = tableName;
    this.whereColumn = whereColumn;
  }

  /**
   * 解析可走范围 COUNT 快路径的 PreparedStatement SQL。
   *
   * @param sql PreparedStatement SQL
   * @return 命中计划；不支持时返回 {@code null}
   */
  static AdbPreparedRangeCountPlan parse(String sql) {
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
    int where = upper.indexOf(WHERE);
    if (from < SELECT_COUNT.length() || where <= from) {
      return null;
    }
    String tablePart = trimmed.substring(from + FROM.length(), where).trim();
    String wherePart = trimmed.substring(where + WHERE.length()).trim();
    String upperWhere = wherePart.toUpperCase(Locale.ROOT);
    int between = upperWhere.indexOf(BETWEEN);
    if (between <= 0) {
      return null;
    }
    String left = wherePart.substring(0, between).trim();
    String right = wherePart.substring(between + BETWEEN.length()).trim();
    if (!PARAM_AND_PARAM.equals(right.toUpperCase(Locale.ROOT))) {
      return null;
    }
    if (tablePart.indexOf(' ') >= 0 || left.indexOf(' ') >= 0) {
      return null;
    }
    return new AdbPreparedRangeCountPlan(normalizeIdentifier(tablePart),
        normalizeIdentifier(left));
  }

  /**
   * 返回该计划需要记录的 JDBC 参数数量。
   *
   * @return 参数数量
   */
  int parameterCount() {
    return 2;
  }

  /**
   * 尝试执行主键范围 COUNT。
   *
   * @param connection h2db 原始连接
   * @param parameters 当前 PreparedStatement 参数
   * @param parameterSet 参数是否已设置
   * @return 命中时返回单行 ResultSet；不安全命中时返回 {@code null}
   * @throws SQLException 查询失败时抛出
   */
  ResultSet tryExecuteQuery(Connection connection, Object[] parameters,
      boolean[] parameterSet) throws SQLException {
    if (parameters == null || parameterSet == null || parameters.length <= 2
        || parameterSet.length <= 2 || !parameterSet[1]
        || !parameterSet[2]) {
      return null;
    }
    long allocationStarted = AdbBenchmarkMain.benchmarkAllocationBytes();
    SessionLocal session = resolveSession(connection);
    AdbBenchmarkMain.recordCurrentMixedStage(
        "plan.rangeCount.resolveSession", allocationStarted,
        AdbBenchmarkMain.benchmarkAllocationBytes());
    allocationStarted = AdbBenchmarkMain.benchmarkAllocationBytes();
    AdbTable table = resolveAdbTable(session);
    AdbBenchmarkMain.recordCurrentMixedStage(
        "plan.rangeCount.resolveTable", allocationStarted,
        AdbBenchmarkMain.benchmarkAllocationBytes());
    if (table == null) {
      return null;
    }
    long startMillis = System.currentTimeMillis();
    Throwable failure = null;
    try {
      long min = toLong(parameters[1]);
      long max = toLong(parameters[2]);
      allocationStarted = AdbBenchmarkMain.benchmarkAllocationBytes();
      long count = min > max ? 0L : countVisibleRows(session, table, min, max);
      AdbBenchmarkMain.recordCurrentMixedStage(
          "plan.rangeCount.countVisible", allocationStarted,
          AdbBenchmarkMain.benchmarkAllocationBytes());
      allocationStarted = AdbBenchmarkMain.benchmarkAllocationBytes();
      try {
        return countResultSet.resultSet(count);
      } finally {
        AdbBenchmarkMain.recordCurrentMixedStage(
            "plan.rangeCount.resultSetBuild", allocationStarted,
            AdbBenchmarkMain.benchmarkAllocationBytes());
      }
    } catch (SQLException e) {
      failure = e;
      throw e;
    } catch (RuntimeException e) {
      failure = e;
      throw e;
    } finally {
      table.recordSqlDiagnostic("SELECT", "RANGE_COUNT_FAST", startMillis,
          failure);
    }
  }

  private long countVisibleRows(SessionLocal session, AdbTable table, long min,
      long max) throws SQLException {
    TxnMap2 map = table.getTxnMap(session);
    RowPrefix prefix = rowPrefix(map, table.getId());
    VersionReadSession sessionView = readSession(table, map);
    long started = System.nanoTime();
    try {
      return map.countVisibleRows(sessionView, prefix, min, max);
    } finally {
      table.recordSqlPhase("ADB_RANGE_COUNT_VISIBLE_COUNT",
          System.nanoTime() - started);
    }
  }

  private VersionReadSession readSession(AdbTable table, TxnMap2 map)
      throws SQLException {
    long modificationId = table.getMaxDataModificationId();
    if (readSession != null && readSessionModificationId == modificationId) {
      return readSession;
    }
    closeReadSession();
    long allocationStarted = AdbBenchmarkMain.benchmarkAllocationBytes();
    readSession = map.openVersionReadSession();
    readSessionModificationId = modificationId;
    AdbBenchmarkMain.recordCurrentMixedStage(
        "plan.rangeCount.readSessionOpen", allocationStarted,
        AdbBenchmarkMain.benchmarkAllocationBytes());
    return readSession;
  }

  /**
   * 关闭 range count 计划持有的可复用读资源。
   *
   * @throws SQLException 底层读会话关闭失败时抛出
   */
  void close() throws SQLException {
    closeReadSession();
  }

  private void closeReadSession() throws SQLException {
    VersionReadSession current = readSession;
    readSession = null;
    readSessionModificationId = Long.MIN_VALUE;
    if (current != null) {
      try {
        current.close();
      } catch (RuntimeException e) {
        throw new SQLException("Failed to close range count read session", e);
      }
    }
  }

  private RowPrefix rowPrefix(TxnMap2 map, int tableId) {
    TabId tabId = map.getTabId(tableId);
    if (tabId.equals(cachedTabId) && cachedRowPrefix != null) {
      return cachedRowPrefix;
    }
    cachedTabId = tabId;
    cachedRowPrefix = RowPrefix.of(tabId);
    return cachedRowPrefix;
  }

  private boolean isPrimaryKeyRange(AdbTable table) {
    Column column = table.getColumn(whereColumn);
    int mainIndexColumn = table.getMainIndexColumn();
    return mainIndexColumn == SearchRow.ROWID_INDEX
        || column.getColumnId() == mainIndexColumn;
  }

  private AdbTable resolveAdbTable(SessionLocal session) {
    if (resolvedTable != null) {
      return resolvedTable;
    }
    AdbTable table = adbTable(session);
    if (table == null || !isPrimaryKeyRange(table)) {
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

  /**
   * 返回当前 PreparedStatement 绑定连接的 H2 session。
   *
   * <p>计划对象随 PreparedStatement 创建，生命周期绑定单个 JDBC 连接；缓存 session
   * 可以避免每次 range count 快路径执行都重复 {@code unwrap(JdbcConnection)}。</p>
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

  private static long toLong(Object value) throws SQLException {
    if (value instanceof Number) {
      return ((Number) value).longValue();
    }
    try {
      return Long.parseLong(String.valueOf(value));
    } catch (NumberFormatException e) {
      throw new SQLException("Range count key is not numeric: " + value, e);
    }
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
