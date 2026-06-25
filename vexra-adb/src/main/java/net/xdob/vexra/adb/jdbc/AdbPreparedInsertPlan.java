package net.xdob.vexra.adb.jdbc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.xdob.vexra.adb.db.AdbTable;
import net.xdob.vexra.adb.db.DynamicByteBuffer;
import org.h2.engine.Session;
import org.h2.engine.SessionLocal;
import org.h2.jdbc.JdbcConnection;
import org.h2.result.DefaultRow;
import org.h2.result.Row;
import org.h2.result.SearchRow;
import org.h2.schema.Schema;
import org.h2.table.Column;
import org.h2.table.Table;
import org.h2.value.Value;
import org.h2.value.ValueNull;
import org.h2.value.ValueToObjectConverter;

/**
 * 简单 VALUES INSERT 的 ADB bulk insert 计划。
 *
 * <p>当前计划只识别 {@code INSERT INTO T(c1, c2) VALUES (?, ?)}
 * 或多组 VALUES 的普通插入。它不接管子查询、表达式、ON DUPLICATE、RETURNING
 * 等需要 h2db Insert 执行器完整语义的形态；不匹配的 SQL 会直接回退到 h2db 原执行路径。</p>
 */
final class AdbPreparedInsertPlan {

  private static final String INSERT_INTO = "INSERT INTO";

  private final String tableName;
  private final List<String> columnNames;
  private final int rowCount;
  private final List<Object> literalValues;
  private AdbTable resolvedTable;
  private Column[] cachedTableColumns;
  private Column[] cachedInsertColumns;
  private SessionLocal cachedSession;

  private AdbPreparedInsertPlan(String tableName, List<String> columnNames,
      int rowCount, List<Object> literalValues) {
    this.tableName = tableName;
    this.columnNames = columnNames;
    this.rowCount = rowCount;
    this.literalValues = literalValues;
  }

  /**
   * 解析可 bulk 化的 PreparedStatement SQL。
   *
   * @param sql PreparedStatement SQL
   * @return 可执行计划；不支持时返回 {@code null}
   */
  static AdbPreparedInsertPlan parse(String sql) {
    if (sql == null) {
      return null;
    }
    String trimmed = sql.trim();
    String upper = trimmed.toUpperCase(Locale.ROOT);
    if (!upper.startsWith(INSERT_INTO)) {
      return null;
    }
    int tableStart = skipWhitespace(trimmed, INSERT_INTO.length());
    int columnStart = trimmed.indexOf('(', tableStart);
    if (columnStart < 0) {
      return null;
    }
    String tableName = trimmed.substring(tableStart, columnStart).trim();
    if (tableName.isEmpty()) {
      return null;
    }
    int columnEnd = findMatching(trimmed, columnStart);
    if (columnEnd < 0) {
      return null;
    }
    List<String> columnNames = splitColumns(
        trimmed.substring(columnStart + 1, columnEnd));
    if (columnNames.isEmpty()) {
      return null;
    }
    int valuesStart = skipWhitespace(trimmed, columnEnd + 1);
    if (!trimmed.regionMatches(true, valuesStart, "VALUES", 0,
        "VALUES".length())) {
      return null;
    }
    String values = trimmed.substring(valuesStart + "VALUES".length()).trim();
    int rowCount = countParameterRows(values, columnNames.size());
    if (rowCount < 1) {
      return null;
    }
    return new AdbPreparedInsertPlan(normalizeIdentifier(tableName),
        columnNames, rowCount, null);
  }

  /**
   * 解析可 bulk 化的 literal 多值 INSERT SQL。
   *
   * @param sql Statement SQL
   * @return 可执行计划；不支持时返回 {@code null}
   */
  static AdbPreparedInsertPlan parseLiteral(String sql) {
    InsertHead head = parseHead(sql);
    if (head == null) {
      return null;
    }
    LiteralRows rows = parseLiteralRows(head.values, head.columnNames.size());
    if (rows == null || rows.rowCount < 1) {
      return null;
    }
    return new AdbPreparedInsertPlan(head.tableName, head.columnNames,
        rows.rowCount, rows.values);
  }

  /**
   * 返回参数数量。
   *
   * @return PreparedStatement 参数数量
   */
  int parameterCount() {
    return columnNames.size() * rowCount;
  }

  /**
   * 尝试执行 ADB bulk insert。
   *
   * @param connection h2db 原始连接
   * @param parameters 当前 PreparedStatement 参数
   * @return 命中 bulk path 时返回写入行数；不能安全命中时返回 {@code null}
   * @throws SQLException bulk 写入失败时抛出
   */
  Integer tryExecute(Connection connection, Object[] parameters,
      boolean[] parameterSet)
      throws SQLException {
    if (!hasAllParameters(parameters, parameterSet)) {
      return null;
    }
    return tryExecuteKnownComplete(connection, parameters);
  }

  /**
   * 执行已确认参数完整的 ADB bulk insert。
   *
   * <p>同一个 PreparedStatement 首次命中 fast path 后，后续执行在未
   * {@code clearParameters()} 的情况下可复用 JDBC 参数完整性结论，避免每批 insert
   * 再扫描所有参数位。调用方仍需负责在清参后回到普通 {@link #tryExecute(Connection,
   * Object[], boolean[])} 路径。</p>
   *
   * @param connection h2db 原始连接
   * @param parameters 当前 PreparedStatement 参数
   * @return 命中 bulk path 时返回写入行数；目标不再是 ADB 表时返回 {@code null}
   * @throws SQLException bulk 写入失败时抛出
   */
  Integer tryExecuteKnownComplete(Connection connection, Object[] parameters)
      throws SQLException {
    if (parameters == null || parameters.length <= parameterCount()) {
      return null;
    }
    SessionLocal session = resolveSession(connection);
    AdbTable table = resolveAdbTable(session);
    if (table == null) {
      return null;
    }
    Integer rawCount = tryExecuteRawAppend(session, table, parameters);
    if (rawCount != null) {
      if (connection.getAutoCommit()) {
        connection.commit();
      }
      return rawCount;
    }
    int count = rowCount == 1
        ? table.bulkInsertAppendRow(session, row(session, table, parameters))
        : table.bulkInsertAppendRows(session, rows(session, table, parameters));
    if (connection.getAutoCommit()) {
      connection.commit();
    }
    return Integer.valueOf(count);
  }

  private Integer tryExecuteRawAppend(SessionLocal session, AdbTable table,
      Object[] parameters) {
    if (rowCount <= 1 || table.getMainIndexColumn() == SearchRow.ROWID_INDEX) {
      return null;
    }
    Column[] tableColumns = tableColumns(table);
    Column[] insertColumns = insertColumns(table);
    if (!isFullTableColumnOrder(tableColumns, insertColumns)) {
      return null;
    }
    int mainIndexColumn = table.getMainIndexColumn();
    if (mainIndexColumn < 0 || mainIndexColumn >= tableColumns.length
        || !supportsRawAppend(tableColumns)) {
      return null;
    }
    long[] rowIds = new long[rowCount];
    byte[][] payloads = new byte[rowCount][];
    int parameter = 1;
    for (int row = 0; row < rowCount; row++) {
      Object[] rawValues = new Object[tableColumns.length];
      for (Column ignored : insertColumns) {
        Object raw = parameters[parameter++];
        if (raw == null) {
          return null;
        }
        rawValues[parameterColumnIndex(insertColumns, parameter - 2)] = raw;
      }
      Object rowId = rawValues[mainIndexColumn];
      if (!(rowId instanceof Number)) {
        return null;
      }
      rowIds[row] = ((Number) rowId).longValue();
      payloads[row] = encodeRawRow(tableColumns, rawValues);
      if (payloads[row] == null) {
        return null;
      }
    }
    return table.tryBulkInsertEncodedAppendRows(session, rowIds, payloads);
  }

  private static int parameterColumnIndex(Column[] insertColumns,
      int parameterIndex) {
    return insertColumns[parameterIndex % insertColumns.length].getColumnId();
  }

  private static boolean isFullTableColumnOrder(Column[] tableColumns,
      Column[] insertColumns) {
    if (tableColumns == null || insertColumns == null
        || tableColumns.length != insertColumns.length) {
      return false;
    }
    for (int i = 0; i < tableColumns.length; i++) {
      if (tableColumns[i] != insertColumns[i]) {
        return false;
      }
    }
    return true;
  }

  private static boolean supportsRawAppend(Column[] columns) {
    for (Column column : columns) {
      int type = column.getType().getValueType();
      if (type != Value.BIGINT && type != Value.VARCHAR) {
        return false;
      }
    }
    return true;
  }

  private static byte[] encodeRawRow(Column[] columns, Object[] values) {
    DynamicByteBuffer row = new DynamicByteBuffer(64);
    row.putInt(Value.ROW);
    row.putInt(columns.length);
    for (int i = 0; i < columns.length; i++) {
      int type = columns[i].getType().getValueType();
      Object value = values[i];
      byte[] encoded;
      if (type == Value.BIGINT && value instanceof Number) {
        encoded = encodeRawBigint(((Number) value).longValue());
      } else if (type == Value.VARCHAR && value instanceof String) {
        encoded = encodeRawVarchar((String) value);
      } else {
        return null;
      }
      row.putInt(encoded.length);
      row.put(encoded);
    }
    return row.toArray();
  }

  private static byte[] encodeRawBigint(long value) {
    return new DynamicByteBuffer(12)
        .putInt(Value.BIGINT)
        .putLong(value)
        .toArray();
  }

  private static byte[] encodeRawVarchar(String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    return new DynamicByteBuffer(8 + bytes.length)
        .putInt(Value.VARCHAR)
        .putInt(bytes.length)
        .put(bytes)
        .toArray();
  }

  /**
   * 执行 literal SQL 的 ADB bulk insert。
   *
   * @param connection h2db 原始连接
   * @return 命中 bulk path 时返回写入行数；不能安全命中时返回 {@code null}
   * @throws SQLException bulk 写入失败时抛出
   */
  Integer tryExecuteLiteral(Connection connection) throws SQLException {
    if (literalValues == null || literalValues.size() != parameterCount()) {
      return null;
    }
    SessionLocal session = resolveSession(connection);
    AdbTable table = resolveAdbTable(session);
    if (table == null) {
      return null;
    }
    int count = rowCount == 1
        ? table.bulkInsertAppendRow(session, row(session, table, literalValues))
        : table.bulkInsertAppendRows(session, rows(session, table, literalValues));
    if (connection.getAutoCommit()) {
      connection.commit();
    }
    return Integer.valueOf(count);
  }

  private List<Row> rows(SessionLocal session, AdbTable table,
      Object[] parameters) {
    Column[] tableColumns = tableColumns(table);
    Column[] insertColumns = insertColumns(table);
    List<Row> rows = new ArrayList<>(rowCount);
    int parameter = 1;
    for (int r = 0; r < rowCount; r++) {
      rows.add(row(session, table, parameters, tableColumns, insertColumns,
          parameter));
      parameter += insertColumns.length;
    }
    return rows;
  }

  private List<Row> rows(SessionLocal session, AdbTable table,
      final List<Object> parameters) {
    Column[] tableColumns = tableColumns(table);
    Column[] insertColumns = insertColumns(table);
    List<Row> rows = new ArrayList<>(rowCount);
    int parameter = 1;
    for (int r = 0; r < rowCount; r++) {
      rows.add(row(session, table, parameters, tableColumns, insertColumns,
          parameter));
      parameter += insertColumns.length;
    }
    return rows;
  }

  private Row row(SessionLocal session, AdbTable table,
      Object[] parameters) {
    return row(session, table, parameters, tableColumns(table),
        insertColumns(table), 1);
  }

  private Row row(SessionLocal session, AdbTable table,
      final List<Object> parameters) {
    return row(session, table, parameters, tableColumns(table),
        insertColumns(table), 1);
  }

  /**
   * 构造一行待写入的 H2 Row。
   *
   * <p>insert/table 列元数据由计划缓存提供；每次执行只创建本行需要的 Value 数组和
   * Row 对象，保持与 h2db insert conversion 一致。</p>
   */
  private Row row(SessionLocal session, AdbTable table,
      Object[] parameters, Column[] tableColumns,
      Column[] insertColumns, int firstParameter) {
    Value[] values = new Value[tableColumns.length];
    java.util.Arrays.fill(values, ValueNull.INSTANCE);
    int parameter = firstParameter;
    for (Column column : insertColumns) {
      Value value = toValue(session, parameters[parameter++]);
      values[column.getColumnId()] = column.convert(session, value);
    }
    DefaultRow row = new DefaultRow(values);
    table.convertInsertRow(session, row, null);
    return row;
  }

  /**
   * 构造一行待写入的 H2 Row。
   *
   * <p>literal Statement fast path 使用 {@link List} 保存已解析参数；这里直接按
   * JDBC 参数的 1-based 编号读取列表下标，避免为每次执行创建临时 accessor 对象。</p>
   */
  private Row row(SessionLocal session, AdbTable table,
      List<Object> parameters, Column[] tableColumns,
      Column[] insertColumns, int firstParameter) {
    Value[] values = new Value[tableColumns.length];
    java.util.Arrays.fill(values, ValueNull.INSTANCE);
    int parameter = firstParameter;
    for (Column column : insertColumns) {
      Value value = toValue(session, parameters.get(parameter - 1));
      parameter++;
      values[column.getColumnId()] = column.convert(session, value);
    }
    DefaultRow row = new DefaultRow(values);
    table.convertInsertRow(session, row, null);
    return row;
  }

  /**
   * 解析并缓存目标 ADB 表。
   *
   * <p>该计划绑定单个 PreparedStatement / literal SQL 形态，命中后重复执行同一表。
   * 缓存表对象可以避免每次 bulk insert 都重新走 schema/table lookup。</p>
   */
  private AdbTable resolveAdbTable(SessionLocal session) {
    if (resolvedTable != null) {
      return resolvedTable;
    }
    Schema schema = session.getDatabase().getSchema(
        session.getCurrentSchemaName());
    String schemaName = null;
    String localTableName = tableName;
    int dot = tableName.indexOf('.');
    if (dot > 0) {
      schemaName = tableName.substring(0, dot);
      localTableName = tableName.substring(dot + 1);
      schema = session.getDatabase().getSchema(schemaName);
    }
    Table table = schema.findTableOrView(session, localTableName);
    if (!(table instanceof AdbTable)) {
      return null;
    }
    resolvedTable = (AdbTable) table;
    return resolvedTable;
  }

  /**
   * 返回目标表列元数据。
   *
   * <p>列数组按表定义只读使用，不在 bulk 路径中修改。</p>
   */
  private Column[] tableColumns(AdbTable table) {
    if (cachedTableColumns == null) {
      cachedTableColumns = table.getColumns();
    }
    return cachedTableColumns;
  }

  /**
   * 返回 INSERT 语句声明的列元数据。
   *
   * <p>PreparedStatement 反复执行时列名不会变化，因此只解析一次列名到 Column。</p>
   */
  private Column[] insertColumns(AdbTable table) {
    if (cachedInsertColumns == null) {
      Column[] columns = new Column[columnNames.size()];
      for (int i = 0; i < columnNames.size(); i++) {
        columns[i] = table.getColumn(columnNames.get(i));
      }
      cachedInsertColumns = columns;
    }
    return cachedInsertColumns;
  }

  /**
   * 返回当前 insert 计划绑定连接的 H2 session。
   *
   * <p>prepared insert 计划随 PreparedStatement 创建；literal 计划只服务单次
   * Statement 执行。缓存 session 可以减少重复 executeUpdate 时的 unwrap 和类型检查成本。</p>
   */
  private SessionLocal resolveSession(Connection connection)
      throws SQLException {
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

  private static Value toValue(SessionLocal session, Object value) {
    if (value == null) {
      return ValueNull.INSTANCE;
    }
    if (value instanceof Value) {
      return (Value) value;
    }
    return ValueToObjectConverter.objectToValue(session, value, Value.UNKNOWN);
  }

  private boolean hasAllParameters(Object[] parameters, boolean[] parameterSet) {
    if (parameters == null || parameters.length <= parameterCount()) {
      return false;
    }
    if (parameterSet == null || parameterSet.length <= parameterCount()) {
      return false;
    }
    for (int i = 1; i <= parameterCount(); i++) {
      if (!parameterSet[i]) {
        return false;
      }
    }
    return true;
  }

  private static int skipWhitespace(String text, int index) {
    int i = index;
    while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
      i++;
    }
    return i;
  }

  private static int findMatching(String text, int start) {
    int depth = 0;
    for (int i = start; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (ch == '(') {
        depth++;
      } else if (ch == ')') {
        depth--;
        if (depth == 0) {
          return i;
        }
      }
    }
    return -1;
  }

  private static List<String> splitColumns(String text) {
    ArrayList<String> result = new ArrayList<>();
    String[] parts = text.split(",");
    for (String part : parts) {
      String column = normalizeIdentifier(part.trim());
      if (column.isEmpty()) {
        return java.util.Collections.emptyList();
      }
      result.add(column);
    }
    return result;
  }

  private static InsertHead parseHead(String sql) {
    if (sql == null) {
      return null;
    }
    String trimmed = sql.trim();
    String upper = trimmed.toUpperCase(Locale.ROOT);
    if (!upper.startsWith(INSERT_INTO)) {
      return null;
    }
    int tableStart = skipWhitespace(trimmed, INSERT_INTO.length());
    int columnStart = trimmed.indexOf('(', tableStart);
    if (columnStart < 0) {
      return null;
    }
    String tableName = trimmed.substring(tableStart, columnStart).trim();
    if (tableName.isEmpty()) {
      return null;
    }
    int columnEnd = findMatching(trimmed, columnStart);
    if (columnEnd < 0) {
      return null;
    }
    List<String> columnNames = splitColumns(
        trimmed.substring(columnStart + 1, columnEnd));
    if (columnNames.isEmpty()) {
      return null;
    }
    int valuesStart = skipWhitespace(trimmed, columnEnd + 1);
    if (!trimmed.regionMatches(true, valuesStart, "VALUES", 0,
        "VALUES".length())) {
      return null;
    }
    return new InsertHead(normalizeIdentifier(tableName), columnNames,
        trimmed.substring(valuesStart + "VALUES".length()).trim());
  }

  private static LiteralRows parseLiteralRows(String values, int columnCount) {
    ArrayList<Object> parsedValues = new ArrayList<>();
    int rows = 0;
    int index = 0;
    while (index < values.length()) {
      index = skipWhitespace(values, index);
      if (index >= values.length() || values.charAt(index) != '(') {
        return null;
      }
      int end = findMatching(values, index);
      if (end < 0) {
        return null;
      }
      List<String> tokens = splitLiteralTuple(values.substring(index + 1, end));
      if (tokens == null || tokens.size() != columnCount) {
        return null;
      }
      for (String token : tokens) {
        Object value = parseLiteralValue(token);
        if (value == UnsupportedLiteral.INSTANCE) {
          return null;
        }
        parsedValues.add(value);
      }
      rows++;
      index = skipWhitespace(values, end + 1);
      if (index >= values.length()) {
        return new LiteralRows(rows, parsedValues);
      }
      if (values.charAt(index) != ',') {
        return null;
      }
      index++;
    }
    return new LiteralRows(rows, parsedValues);
  }

  private static List<String> splitLiteralTuple(String tuple) {
    ArrayList<String> tokens = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inString = false;
    for (int i = 0; i < tuple.length(); i++) {
      char ch = tuple.charAt(i);
      if (ch == '\'') {
        current.append(ch);
        if (inString && i + 1 < tuple.length() && tuple.charAt(i + 1) == '\'') {
          current.append('\'');
          i++;
        } else {
          inString = !inString;
        }
      } else if (ch == ',' && !inString) {
        tokens.add(current.toString().trim());
        current.setLength(0);
      } else {
        current.append(ch);
      }
    }
    if (inString) {
      return null;
    }
    tokens.add(current.toString().trim());
    return tokens;
  }

  private static Object parseLiteralValue(String token) {
    if (token == null || token.isEmpty()) {
      return UnsupportedLiteral.INSTANCE;
    }
    String upper = token.toUpperCase(Locale.ROOT);
    if ("NULL".equals(upper)) {
      return null;
    }
    if ("TRUE".equals(upper)) {
      return Boolean.TRUE;
    }
    if ("FALSE".equals(upper)) {
      return Boolean.FALSE;
    }
    if (token.startsWith("'") && token.endsWith("'") && token.length() >= 2) {
      return token.substring(1, token.length() - 1).replace("''", "'");
    }
    try {
      if (token.indexOf('.') >= 0 || token.indexOf('E') >= 0
          || token.indexOf('e') >= 0) {
        return new BigDecimal(token);
      }
      long value = Long.parseLong(token);
      if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
        return Integer.valueOf((int) value);
      }
      return Long.valueOf(value);
    } catch (NumberFormatException e) {
      return UnsupportedLiteral.INSTANCE;
    }
  }

  private static int countParameterRows(String values, int columnCount) {
    int rows = 0;
    int index = 0;
    while (index < values.length()) {
      index = skipWhitespace(values, index);
      if (index >= values.length() || values.charAt(index) != '(') {
        return -1;
      }
      int end = findMatching(values, index);
      if (end < 0) {
        return -1;
      }
      if (!isParameterTuple(values.substring(index + 1, end), columnCount)) {
        return -1;
      }
      rows++;
      index = skipWhitespace(values, end + 1);
      if (index >= values.length()) {
        return rows;
      }
      if (values.charAt(index) != ',') {
        return -1;
      }
      index++;
    }
    return rows;
  }

  private static boolean isParameterTuple(String tuple, int columnCount) {
    String[] parts = tuple.split(",");
    if (parts.length != columnCount) {
      return false;
    }
    for (String part : parts) {
      if (!"?".equals(part.trim())) {
        return false;
      }
    }
    return true;
  }

  private static String normalizeIdentifier(String identifier) {
    String value = identifier.trim();
    if (value.startsWith("\"") && value.endsWith("\"")
        && value.length() >= 2) {
      return value.substring(1, value.length() - 1);
    }
    return value.toUpperCase(Locale.ROOT);
  }

  private static final class InsertHead {
    private final String tableName;
    private final List<String> columnNames;
    private final String values;

    private InsertHead(String tableName, List<String> columnNames,
        String values) {
      this.tableName = tableName;
      this.columnNames = columnNames;
      this.values = values;
    }
  }

  private static final class LiteralRows {
    private final int rowCount;
    private final List<Object> values;

    private LiteralRows(int rowCount, List<Object> values) {
      this.rowCount = rowCount;
      this.values = values;
    }
  }

  private enum UnsupportedLiteral {
    INSTANCE
  }
}
