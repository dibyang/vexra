package net.xdob.vexra.adb.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import net.xdob.vexra.adb.AdbBenchmarkMain;
import net.xdob.vexra.adb.db.AdbTable;
import net.xdob.vexra.adb.db.RowCodec;
import net.xdob.vexra.adb.db.RowValue;
import net.xdob.vexra.adb.db.TxnMap2;
import net.xdob.vexra.adb.db.TxnManager;
import net.xdob.vexra.adb.db.VersionReadSession;
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

  private static final int DECODED_COLUMN_CACHE_LIMIT =
      Integer.getInteger("adb.pointLookup.fastDecodedColumnCacheLimit",
          16_384);
  private static final int VALUE_CACHE_MODIFICATION_CHANGE_LIMIT =
      Integer.getInteger("adb.pointLookup.valueCacheModificationChangeLimit",
          8);
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
  private SessionLocal cachedSession;
  private AdbSimpleResultSet.SingleValueResultSet singleValueResultSet;
  private VersionReadSession visibleColumnReadSession;
  private long visibleColumnReadSessionModificationId = Long.MIN_VALUE;
  private final ConcurrentHashMap<Long, CachedColumnValues> decodedColumnCache =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Long, CachedSingleColumnValue>
      decodedSingleColumnCache = new ConcurrentHashMap<>();
  private volatile long decodedCacheTableModificationId = Long.MIN_VALUE;
  private int decodedCacheTableModificationChanges;
  private boolean directValueCacheDisabled;

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
    long allocationStarted = AdbBenchmarkMain.benchmarkAllocationBytes();
    SessionLocal session = resolveSession(connection);
    AdbBenchmarkMain.recordCurrentMixedStage(
        "plan.pointLookup.resolveSession", allocationStarted,
        AdbBenchmarkMain.benchmarkAllocationBytes());
    allocationStarted = AdbBenchmarkMain.benchmarkAllocationBytes();
    AdbTable table = resolveAdbTable(session);
    AdbBenchmarkMain.recordCurrentMixedStage(
        "plan.pointLookup.resolveTable", allocationStarted,
        AdbBenchmarkMain.benchmarkAllocationBytes());
    if (table == null) {
      return null;
    }
    resetDecodedCacheIfTableChanged(table);
    long startMillis = System.currentTimeMillis();
    Throwable failure = null;
    try {
      long rowId = toLong(parameters[1]);
      boolean singleColumn = resolvedColumnIds.length == 1;
      allocationStarted = AdbBenchmarkMain.benchmarkAllocationBytes();
      Value cachedSingleValue = singleColumn
          ? cachedSingleValue(table, rowId) : null;
      AdbBenchmarkMain.recordCurrentMixedStage(
          "plan.pointLookup.cacheLookup", allocationStarted,
          AdbBenchmarkMain.benchmarkAllocationBytes());
      if (cachedSingleValue != null) {
        if (!detailedSqlDiagnostics()) {
          allocationStarted = AdbBenchmarkMain.benchmarkAllocationBytes();
          try {
            return resultSet(true, cachedSingleValue, null);
          } finally {
            AdbBenchmarkMain.recordCurrentMixedStage(
                "plan.pointLookup.resultSetBuild", allocationStarted,
                AdbBenchmarkMain.benchmarkAllocationBytes());
          }
        }
        long resultStarted = System.nanoTime();
        try {
          allocationStarted = AdbBenchmarkMain.benchmarkAllocationBytes();
          try {
            return resultSet(true, cachedSingleValue, null);
          } finally {
            AdbBenchmarkMain.recordCurrentMixedStage(
                "plan.pointLookup.resultSetBuild", allocationStarted,
                AdbBenchmarkMain.benchmarkAllocationBytes());
          }
        } finally {
          table.recordSqlPhase("ADB_POINT_LOOKUP_RESULT_BUILD",
              System.nanoTime() - resultStarted);
        }
      }
      allocationStarted = AdbBenchmarkMain.benchmarkAllocationBytes();
      TxnManager.VisibleColumnValue visibleColumn = singleColumn
          ? visibleSingleColumnValue(session, table, rowId) : null;
      AdbBenchmarkMain.recordCurrentMixedStage(
          "plan.pointLookup.visibleColumn", allocationStarted,
          AdbBenchmarkMain.benchmarkAllocationBytes());
      allocationStarted = AdbBenchmarkMain.benchmarkAllocationBytes();
      RowValue rowValue = !singleColumn
          ? visibleRowValue(session, table, rowId) : null;
      AdbBenchmarkMain.recordCurrentMixedStage(
          "plan.pointLookup.visibleRow", allocationStarted,
          AdbBenchmarkMain.benchmarkAllocationBytes());
      if ((singleColumn && visibleColumn == null)
          || (!singleColumn && rowValue == null)) {
        decodedColumnCache.remove(Long.valueOf(rowId));
        decodedSingleColumnCache.remove(Long.valueOf(rowId));
      }
      allocationStarted = AdbBenchmarkMain.benchmarkAllocationBytes();
      Value singleValue = singleColumn && visibleColumn != null
          ? decodedSingleValue(table, rowId, visibleColumn) : null;
      Value[] values = !singleColumn && rowValue != null
          ? decodedValues(table, rowId, rowValue) : null;
      AdbBenchmarkMain.recordCurrentMixedStage(
          "plan.pointLookup.decode", allocationStarted,
          AdbBenchmarkMain.benchmarkAllocationBytes());
      if (!detailedSqlDiagnostics()) {
        allocationStarted = AdbBenchmarkMain.benchmarkAllocationBytes();
        try {
          return resultSet(singleColumn, singleValue, values);
        } finally {
          AdbBenchmarkMain.recordCurrentMixedStage(
              "plan.pointLookup.resultSetBuild", allocationStarted,
              AdbBenchmarkMain.benchmarkAllocationBytes());
        }
      }
      long resultStarted = System.nanoTime();
      try {
        allocationStarted = AdbBenchmarkMain.benchmarkAllocationBytes();
        try {
          return resultSet(singleColumn, singleValue, values);
        } finally {
          AdbBenchmarkMain.recordCurrentMixedStage(
              "plan.pointLookup.resultSetBuild", allocationStarted,
              AdbBenchmarkMain.benchmarkAllocationBytes());
        }
      } finally {
        table.recordSqlPhase("ADB_POINT_LOOKUP_RESULT_BUILD",
            System.nanoTime() - resultStarted);
      }
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

  private TxnManager.VisibleColumnValue visibleSingleColumnValue(
      SessionLocal session, AdbTable table, long rowId) throws SQLException {
    TxnMap2 map = table.getTxnMap(session);
    long allocationStarted = AdbBenchmarkMain.benchmarkAllocationBytes();
    net.xdob.vexra.adb.key.TabId tabId = map.getTabId(table.getId());
    AdbBenchmarkMain.recordCurrentMixedStage(
        "plan.pointLookup.visibleColumn.tabId", allocationStarted,
        AdbBenchmarkMain.benchmarkAllocationBytes());
    allocationStarted = AdbBenchmarkMain.benchmarkAllocationBytes();
    RowKey rowKey = RowKey.of(tabId, rowId);
    AdbBenchmarkMain.recordCurrentMixedStage(
        "plan.pointLookup.visibleColumn.rowKey", allocationStarted,
        AdbBenchmarkMain.benchmarkAllocationBytes());
    VersionReadSession readSession = visibleColumnReadSession(table, map);
    if (!detailedSqlDiagnostics()) {
      return map.getVisibleColumn(readSession, rowKey, resolvedColumnIds[0]);
    }
    long started = System.nanoTime();
    try {
      return map.getVisibleColumn(readSession, rowKey, resolvedColumnIds[0]);
    } finally {
      table.recordSqlPhase("ADB_POINT_LOOKUP_VISIBLE_ROW",
          System.nanoTime() - started);
    }
  }

  private VersionReadSession visibleColumnReadSession(AdbTable table,
      TxnMap2 map) throws SQLException {
    long tableModificationId = table.getMaxDataModificationId();
    if (visibleColumnReadSession != null
        && visibleColumnReadSessionModificationId == tableModificationId) {
      return visibleColumnReadSession;
    }
    closeVisibleColumnReadSession();
    long allocationStarted = AdbBenchmarkMain.benchmarkAllocationBytes();
    visibleColumnReadSession = map.openVersionReadSession();
    visibleColumnReadSessionModificationId = tableModificationId;
    AdbBenchmarkMain.recordCurrentMixedStage(
        "plan.pointLookup.visibleColumn.readSessionOpen",
        allocationStarted, AdbBenchmarkMain.benchmarkAllocationBytes());
    return visibleColumnReadSession;
  }

  /**
   * 关闭点查计划持有的可复用读资源。
   *
   * @throws SQLException 底层读会话关闭失败时抛出
   */
  void close() throws SQLException {
    closeVisibleColumnReadSession();
  }

  private void closeVisibleColumnReadSession() throws SQLException {
    VersionReadSession readSession = visibleColumnReadSession;
    visibleColumnReadSession = null;
    visibleColumnReadSessionModificationId = Long.MIN_VALUE;
    if (readSession != null) {
      try {
        readSession.close();
      } catch (RuntimeException e) {
        throw new SQLException("Failed to close point lookup read session", e);
      }
    }
  }

  private ResultSet resultSet(boolean singleColumn, Value singleValue,
      Value[] values) {
    if (singleColumn) {
      return singleValueResultSet(resolvedSelectColumns.get(0))
          .resultSet(singleValue);
    }
    return AdbSimpleResultSet.singleRow(resolvedSelectColumns, values);
  }

  private AdbSimpleResultSet.SingleValueResultSet singleValueResultSet(
      String columnName) {
    if (singleValueResultSet == null) {
      singleValueResultSet = AdbSimpleResultSet.reusableSingleValue(
          columnName);
    }
    return singleValueResultSet;
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
    if (!detailedSqlDiagnostics()) {
      RowValue rowValue = map.getVisible(rowKey);
      if (rowValue == null || rowValue.deleted || rowValue.payload == null
          || rowValue.payload.length == 0) {
        return null;
      }
      return rowValue;
    }
    long started = System.nanoTime();
    try {
      RowValue rowValue = map.getVisible(rowKey);
      if (rowValue == null || rowValue.deleted || rowValue.payload == null
          || rowValue.payload.length == 0) {
        return null;
      }
      return rowValue;
    } finally {
      table.recordSqlPhase("ADB_POINT_LOOKUP_VISIBLE_ROW",
          System.nanoTime() - started);
    }
  }

  private Value[] decodedValues(AdbTable table, long rowId,
      RowValue rowValue) {
    long started = System.nanoTime();
    if (rowValue.commitTs <= 0L) {
      try {
        return RowCodec.decodeColumns(rowValue.payload, resolvedColumnIds);
      } finally {
        table.recordSqlPhase("ADB_POINT_LOOKUP_DECODE_CACHE_MISS",
            System.nanoTime() - started);
      }
    }
    CachedColumnValues cached = decodedColumnCache.get(rowId);
    if (cached != null && cached.commitTs == rowValue.commitTs) {
      table.recordSqlPhase("ADB_POINT_LOOKUP_DECODE_CACHE_HIT",
          System.nanoTime() - started);
      return cached.values();
    }
    try {
      Value[] values = RowCodec.decodeColumns(rowValue.payload,
          resolvedColumnIds);
      cacheDecodedValues(rowId, rowValue.commitTs, values);
      return values;
    } finally {
      table.recordSqlPhase("ADB_POINT_LOOKUP_DECODE_CACHE_MISS",
          System.nanoTime() - started);
    }
  }

  private Value decodedSingleValue(AdbTable table, long rowId,
      RowValue rowValue) {
    long started = System.nanoTime();
    if (rowValue.commitTs <= 0L) {
      try {
        return RowCodec.decodeColumn(rowValue.payload, resolvedColumnIds[0]);
      } finally {
        table.recordSqlPhase("ADB_POINT_LOOKUP_DECODE_CACHE_MISS",
            System.nanoTime() - started);
      }
    }
    CachedSingleColumnValue cached = decodedSingleColumnCache.get(rowId);
    if (cached != null && cached.commitTs == rowValue.commitTs) {
      table.recordSqlPhase("ADB_POINT_LOOKUP_DECODE_CACHE_HIT",
          System.nanoTime() - started);
      return cached.value();
    }
    try {
      Value value = RowCodec.decodeColumn(rowValue.payload,
          resolvedColumnIds[0]);
      cacheDecodedSingleValue(rowId, rowValue.commitTs, value);
      return value;
    } finally {
      table.recordSqlPhase("ADB_POINT_LOOKUP_DECODE_CACHE_MISS",
          System.nanoTime() - started);
    }
  }

  private Value decodedSingleValue(AdbTable table, long rowId,
      TxnManager.VisibleColumnValue visibleColumn) {
    long started = System.nanoTime();
    long commitTs = visibleColumn.commitTs();
    if (commitTs <= 0L) {
      table.recordSqlPhase("ADB_POINT_LOOKUP_DECODE_CACHE_MISS",
          System.nanoTime() - started);
      return visibleColumn.value();
    }
    CachedSingleColumnValue cached = decodedSingleColumnCache.get(rowId);
    if (cached != null && cached.commitTs == commitTs) {
      table.recordSqlPhase("ADB_POINT_LOOKUP_DECODE_CACHE_HIT",
          System.nanoTime() - started);
      return cached.value();
    }
    try {
      Value value = visibleColumn.value();
      cacheDecodedSingleValue(table, rowId, commitTs, value,
          visibleColumn.latestCommitted());
      return value;
    } finally {
      table.recordSqlPhase("ADB_POINT_LOOKUP_DECODE_CACHE_MISS",
          System.nanoTime() - started);
    }
  }

  private Value cachedSingleValue(AdbTable table, long rowId) {
    long started = System.nanoTime();
    try {
      if (directValueCacheDisabled) {
        table.recordSqlPhase("ADB_POINT_LOOKUP_VALUE_CACHE_DISABLED",
            System.nanoTime() - started);
        return null;
      }
      if (decodedSingleColumnCache.isEmpty()) {
        table.recordSqlPhase("ADB_POINT_LOOKUP_VALUE_CACHE_MISS",
            System.nanoTime() - started);
        return null;
      }
      CachedSingleColumnValue cached = decodedSingleColumnCache.get(rowId);
      if (cached != null
          && cached.latestCommitted
          && cached.tableModificationId == table.getMaxDataModificationId()) {
        table.recordSqlPhase("ADB_POINT_LOOKUP_VALUE_CACHE_HIT",
            System.nanoTime() - started);
        return cached.value();
      }
      table.recordSqlPhase("ADB_POINT_LOOKUP_VALUE_CACHE_MISS",
          System.nanoTime() - started);
      return null;
    } catch (RuntimeException e) {
      table.recordSqlPhase("ADB_POINT_LOOKUP_VALUE_CACHE_MISS",
          System.nanoTime() - started);
      throw e;
    }
  }

  private void cacheDecodedValues(long rowId, long commitTs, Value[] values) {
    if (DECODED_COLUMN_CACHE_LIMIT <= 0) {
      return;
    }
    if (decodedColumnCache.size() >= DECODED_COLUMN_CACHE_LIMIT) {
      decodedColumnCache.clear();
    }
    decodedColumnCache.put(Long.valueOf(rowId),
        new CachedColumnValues(commitTs, values));
  }

  private void cacheDecodedSingleValue(long rowId, long commitTs,
      Value value) {
    cacheDecodedSingleValue(null, rowId, commitTs, value, false);
  }

  private void cacheDecodedSingleValue(AdbTable table, long rowId,
      long commitTs, Value value, boolean latestCommitted) {
    if (DECODED_COLUMN_CACHE_LIMIT <= 0) {
      return;
    }
    if (decodedSingleColumnCache.size() >= DECODED_COLUMN_CACHE_LIMIT) {
      decodedSingleColumnCache.clear();
    }
    decodedSingleColumnCache.put(Long.valueOf(rowId),
        new CachedSingleColumnValue(commitTs, value,
            table == null ? -1L : table.getMaxDataModificationId(),
            latestCommitted));
  }

  private void resetDecodedCacheIfTableChanged(AdbTable table) {
    long tableModificationId = table.getMaxDataModificationId();
    if (decodedCacheTableModificationId == tableModificationId) {
      return;
    }
    if (decodedCacheTableModificationId != Long.MIN_VALUE
        && VALUE_CACHE_MODIFICATION_CHANGE_LIMIT >= 0
        && ++decodedCacheTableModificationChanges
            >= VALUE_CACHE_MODIFICATION_CHANGE_LIMIT) {
      directValueCacheDisabled = true;
    }
    decodedColumnCache.clear();
    decodedSingleColumnCache.clear();
    decodedCacheTableModificationId = tableModificationId;
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
   * <p>点查计划随 PreparedStatement 创建，生命周期绑定单个 JDBC 连接；缓存 session
   * 可以避免每次 point lookup 快路径执行都重复 unwrap 和类型检查。</p>
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

  private static boolean detailedSqlDiagnostics() {
    return Boolean.getBoolean("vexra.adb.sql.diagnostic.detail");
  }

  private static final class CachedColumnValues {
    private final long commitTs;
    private final Value[] values;

    private CachedColumnValues(long commitTs, Value[] values) {
      this.commitTs = commitTs;
      this.values = values;
    }

    private Value[] values() {
      return values;
    }
  }

  private static final class CachedSingleColumnValue {
    private final long commitTs;
    private final Value value;
    private final long tableModificationId;
    private final boolean latestCommitted;

    private CachedSingleColumnValue(long commitTs, Value value,
        long tableModificationId, boolean latestCommitted) {
      this.commitTs = commitTs;
      this.value = value;
      this.tableModificationId = tableModificationId;
      this.latestCommitted = latestCommitted;
    }

    private Value value() {
      return value;
    }
  }
}
