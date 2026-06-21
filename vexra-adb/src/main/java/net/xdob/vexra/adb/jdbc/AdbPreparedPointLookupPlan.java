package net.xdob.vexra.adb.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.xdob.vexra.adb.db.AdbTable;
import net.xdob.vexra.adb.db.RowCodec;
import net.xdob.vexra.adb.db.RowValue;
import net.xdob.vexra.adb.db.TxnMap2;
import net.xdob.vexra.adb.key.RowKey;
import org.h2.engine.Session;
import org.h2.engine.SessionLocal;
import org.h2.jdbc.JdbcConnection;
import org.h2.result.SearchRow;
import org.h2.schema.Schema;
import org.h2.table.Column;
import org.h2.table.Table;
import org.h2.value.Value;

/**
 * 参数化主键点查的 ADB 快路径计划。
 *
 * <p>当前仅识别 {@code SELECT col[, ...] FROM table WHERE pk = ?}，并在执行时确认
 * {@code WHERE} 列是 ADB 表主键列。其他查询继续回退 h2db 原执行器。</p>
 */
final class AdbPreparedPointLookupPlan {

  private static final String SELECT = "SELECT";
  private static final String FROM = " FROM ";
  private static final String WHERE = " WHERE ";

  private final List<String> selectColumns;
  private final String tableName;
  private final String whereColumn;
  private final boolean selectAllColumns;
  private AdbTable resolvedTable;
  private List<String> resolvedSelectColumns;
  private int[] resolvedColumnIds;

  private AdbPreparedPointLookupPlan(List<String> selectColumns,
      String tableName, String whereColumn, boolean selectAllColumns) {
    this.selectColumns = selectColumns;
    this.tableName = tableName;
    this.whereColumn = whereColumn;
    this.selectAllColumns = selectAllColumns;
  }

  /**
   * 解析可点查快路径化的 SQL。
   *
   * @param sql PreparedStatement SQL
   * @return 可执行计划；不支持时返回 {@code null}
   */
  static AdbPreparedPointLookupPlan parse(String sql) {
    if (sql == null) {
      return null;
    }
    String trimmed = sql.trim();
    if (trimmed.endsWith(";")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
    }
    String upper = trimmed.toUpperCase(Locale.ROOT);
    if (!upper.startsWith(SELECT + " ")) {
      return null;
    }
    int from = upper.indexOf(FROM);
    int where = upper.indexOf(WHERE);
    if (from <= SELECT.length() || where <= from) {
      return null;
    }
    String selectPart = trimmed.substring(SELECT.length(), from).trim();
    String tablePart = trimmed.substring(from + FROM.length(), where).trim();
    String wherePart = trimmed.substring(where + WHERE.length()).trim();
    if (selectPart.isEmpty() || tablePart.isEmpty()) {
      return null;
    }
    int equals = wherePart.indexOf('=');
    if (equals <= 0) {
      return null;
    }
    String left = wherePart.substring(0, equals).trim();
    String right = wherePart.substring(equals + 1).trim();
    if (!"?".equals(right)) {
      return null;
    }
    if (tablePart.indexOf(' ') >= 0 || left.indexOf(' ') >= 0) {
      return null;
    }
    boolean selectAll = "*".equals(selectPart.trim());
    List<String> columns = selectAll ? java.util.Collections.<String>emptyList()
        : splitColumns(selectPart);
    if (!selectAll && columns.isEmpty()) {
      return null;
    }
    return new AdbPreparedPointLookupPlan(columns,
        normalizeIdentifier(tablePart), normalizeIdentifier(left), selectAll);
  }

  /**
   * 返回该快路径计划需要记录的 JDBC 参数数量。
   *
   * @return 参数数量
   */
  int parameterCount() {
    return 1;
  }

  /**
   * 尝试执行主键点查。
   *
   * @param connection h2db 原始连接
   * @param parameters 当前 PreparedStatement 参数
   * @param parameterSet 参数是否已设置
   * @return 命中时返回 ResultSet；不能安全命中时返回 {@code null}
   * @throws SQLException 查询失败时抛出
   */
  ResultSet tryExecuteQuery(Connection connection, Object[] parameters,
      boolean[] parameterSet) throws SQLException {
    if (parameters == null || parameterSet == null || parameters.length <= 1
        || parameterSet.length <= 1 || !parameterSet[1]) {
      return null;
    }
    SessionLocal session = session(connection);
    AdbTable table = resolveAdbTable(session);
    if (table == null) {
      return null;
    }
    long startMillis = System.currentTimeMillis();
    Throwable failure = null;
    try {
      long rowId = toLong(parameters[1]);
      RowValue rowValue = visibleRowValue(session, table, rowId);
      Value[] values = rowValue == null ? null
          : RowCodec.decodeColumns(rowValue.payload, resolvedColumnIds);
      return AdbSimpleResultSet.singleRow(resolvedSelectColumns, values);
    } catch (SQLException e) {
      failure = e;
      throw e;
    } catch (RuntimeException e) {
      failure = e;
      throw e;
    } finally {
      table.recordSqlDiagnostic("SELECT", "POINT_LOOKUP_FAST", startMillis,
          failure);
    }
  }

  private boolean isPrimaryKeyLookup(AdbTable table) {
    Column column = table.getColumn(whereColumn);
    int mainIndexColumn = table.getMainIndexColumn();
    return mainIndexColumn == SearchRow.ROWID_INDEX
        || column.getColumnId() == mainIndexColumn;
  }

  private int[] selectedColumnIds(AdbTable table) {
    List<String> columns = selectAllColumns ? allColumnNames(table)
        : selectColumns;
    int[] columnIds = new int[columns.size()];
    for (int i = 0; i < columns.size(); i++) {
      Column column = table.getColumn(columns.get(i));
      columnIds[i] = column.getColumnId();
    }
    resolvedSelectColumns = columns;
    return columnIds;
  }

  private static List<String> allColumnNames(AdbTable table) {
    Column[] tableColumns = table.getColumns();
    ArrayList<String> names = new ArrayList<>(tableColumns.length);
    for (Column column : tableColumns) {
      names.add(column.getName());
    }
    return names;
  }

  private AdbTable resolveAdbTable(SessionLocal session) {
    if (resolvedTable != null) {
      return resolvedTable;
    }
    AdbTable table = adbTable(session);
    if (table == null || !isPrimaryKeyLookup(table)) {
      return null;
    }
    resolvedColumnIds = selectedColumnIds(table);
    resolvedTable = table;
    return table;
  }

  private RowValue visibleRowValue(SessionLocal session, AdbTable table,
      long rowId) throws SQLException {
    TxnMap2 map = table.getTxnMap(session);
    RowKey rowKey = RowKey.of(map.getTabId(table.getId()), rowId);
    RowValue rowValue = map.getVisible(rowKey);
    if (rowValue == null || rowValue.deleted || rowValue.payload == null
        || rowValue.payload.length == 0) {
      return null;
    }
    return rowValue;
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

  private static SessionLocal session(Connection connection) throws SQLException {
    Session session = connection.unwrap(JdbcConnection.class).getSession();
    if (!(session instanceof SessionLocal)) {
      throw new SQLException("Unsupported H2 session type: "
          + session.getClass().getName());
    }
    return (SessionLocal) session;
  }

  private static long toLong(Object value) throws SQLException {
    if (value instanceof Number) {
      return ((Number) value).longValue();
    }
    try {
      return Long.parseLong(String.valueOf(value));
    } catch (NumberFormatException e) {
      throw new SQLException("Point lookup key is not numeric: " + value, e);
    }
  }

  private static List<String> splitColumns(String text) {
    ArrayList<String> result = new ArrayList<>();
    String[] parts = text.split(",");
    for (String part : parts) {
      String column = normalizeIdentifier(part.trim());
      if (column.isEmpty() || "*".equals(column)) {
        return java.util.Collections.emptyList();
      }
      result.add(column);
    }
    return result;
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
