package net.xdob.vexra.adb.db;


import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.RowPrefix;
import org.h2.api.ErrorCode;
import org.h2.command.query.AllColumnsForPlan;
import org.h2.engine.Database;
import org.h2.engine.SessionLocal;
import org.h2.index.Cursor;
import org.h2.index.IndexType;
import org.h2.index.SingleRowCursor;
import org.h2.message.DbException;
import org.h2.mvstore.MVStoreException;
import org.h2.result.DefaultRow;
import org.h2.result.Row;
import org.h2.result.SearchRow;
import org.h2.result.SortOrder;
import org.h2.table.Column;
import org.h2.table.IndexColumn;
import org.h2.table.TableFilter;
import org.h2.value.Value;
import org.h2.value.ValueLob;
import org.h2.value.ValueNull;

/**
 * A table stored in a RocksStore.
 */
public class AdbPrimaryIndex extends AdbIndex<Long, SearchRow> {
  private static final int DECODED_ROW_CACHE_LIMIT =
      Integer.getInteger("adb.pointLookup.decodedRowCacheLimit", 16_384);

  private final AdbTable rocksTable;
  private final ConcurrentHashMap<RowKey, CachedDecodedRow> decodedRowCache =
      new ConcurrentHashMap<>();


  private int mainIndexColumn = SearchRow.ROWID_INDEX;

  public AdbPrimaryIndex(Database db, AdbTable table, int id, IndexColumn[] columns, IndexType indexType) {
    super(table, id, table.getName() + "_DATA", columns, 0, indexType);
    this.rocksTable = table;

  }


  @Override
  public String getCreateSQL() {
    return null;
  }

  @Override
  public String getPlanSQL() {
    String plan = table.getSQL(new StringBuilder(), TRACE_SQL_FLAGS)
        .append(".tableScan").toString();
    String marker = getDistributedPlanMarker();
    return marker.isEmpty() ? plan : plan + " " + marker;
  }

  String getDistributedPlanMarker() {
    AdbSqlDistributedScanRuntime runtime =
        rocksTable.getSqlDistributedScanRuntime();
    if (runtime != null && runtime.isEnabled()) {
      return runtime.getPlanMarker();
    }
    return "";
  }

  public void setMainIndexColumn(int mainIndexColumn) {
    this.mainIndexColumn = mainIndexColumn;
  }

  public int getMainIndexColumn() {
    return mainIndexColumn;
  }

  @Override
  public void close(SessionLocal session) {
    // ok
  }

  @Override
  public void add(SessionLocal session, Row row) {
    if (mainIndexColumn == SearchRow.ROWID_INDEX) {
      if (row.getKey() == 0) {
        row.setKey(rocksTable.nextKey());
      }
    } else {
      long c = row.getValue(mainIndexColumn).getLong();
      row.setKey(c);
    }

    if (rocksTable.getContainsLargeObject()) {
      for (int i = 0, len = row.getColumnCount(); i < len; i++) {
        Value v = row.getValue(i);
        if (v instanceof ValueLob) {
          ValueLob lob = ((ValueLob) v).copy(database, getId());
          session.removeAtCommitStop(lob);
          if (v != lob) {
            row.setValue(i, lob);
          }
        }
      }
    }


    TxnMap2 map = getTxnMap(session);
    long rowId = row.getKey();
    try {
      RowKey rowKey = RowKey.of(map.getTabId(table.getId()), rowId);
      boolean skipAppendUniqueCheck = map.canSkipAppendUniqueCheck(rowKey);
      if (!skipAppendUniqueCheck) {
        map.lock(table.getId(), rowId, session.getLockTimeout());
      }
      RowValue old = map.putIfAbsent(rowKey, row, skipAppendUniqueCheck);
      if (old != null) {
        int errorCode = ErrorCode.CONCURRENT_UPDATE_1;
        if (map.getVisible(rowKey) != null) {
          // committed
          errorCode = ErrorCode.DUPLICATE_KEY_1;
        }
        Row oldRow = RowCodec.decode(rowId, old.payload);
        DbException e = DbException.get(errorCode,
            getDuplicatePrimaryKeyMessage(mainIndexColumn).append(' ').append(oldRow).toString());
        e.setSource(this);
        throw e;
      }
    } catch (SQLException e) {
      throw rocksTable.convertException(e);
    }

  }

  @Override
  public void remove(SessionLocal session, Row row) {
    if (rocksTable.getContainsLargeObject()) {
      for (int i = 0, len = row.getColumnCount(); i < len; i++) {
        Value v = row.getValue(i);
        if (v instanceof ValueLob) {
          session.removeAtCommit((ValueLob) v);
        }
      }
    }
    TxnMap2 map = getTxnMap(session);
    try {
      RowKey rowKey = RowKey.of(map.getTabId(table.getId()), row.getKey());
      RowValue existing = map.delete(rowKey);
      if (existing == null) {
        StringBuilder builder = new StringBuilder();
        getSQL(builder, TRACE_SQL_FLAGS).append(": ").append(row.getKey());
        throw DbException.get(ErrorCode.ROW_NOT_FOUND_WHEN_DELETING_1, builder.toString());
      }
    } catch (SQLException e) {
      throw rocksTable.convertException(e);
    }
  }

  @Override
  public void update(SessionLocal session, Row oldRow, Row newRow) {
    if (oldRow.getKey() != newRow.getKey()) {
      throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1, "update primary key");
    }
    if (mainIndexColumn != SearchRow.ROWID_INDEX) {
      long c = newRow.getValue(mainIndexColumn).getLong();
      newRow.setKey(c);
    }
    long key = oldRow.getKey();
    assert mainIndexColumn != SearchRow.ROWID_INDEX || key != 0;
    assert key == newRow.getKey() : key + " != " + newRow.getKey();
    if (rocksTable.getContainsLargeObject()) {
      for (int i = 0, len = oldRow.getColumnCount(); i < len; i++) {
        Value oldValue = oldRow.getValue(i);
        Value newValue = newRow.getValue(i);
        if (oldValue != newValue) {
          if (oldValue instanceof ValueLob) {
            session.removeAtCommit((ValueLob) oldValue);
          }
          if (newValue instanceof ValueLob) {
            ValueLob lob = ((ValueLob) newValue).copy(database, getId());
            session.removeAtCommitStop(lob);
            if (newValue != lob) {
              newRow.setValue(i, lob);
            }
          }
        }
      }
    }

    TxnMap2 map = getTxnMap(session);
    try {
      RowKey rowKey = RowKey.of(map.getTabId(table.getId()), key);
      RowValue existing = map.put(rowKey, newRow);
      if (existing == null) {
        StringBuilder builder = new StringBuilder();
        getSQL(builder, TRACE_SQL_FLAGS).append(": ").append(key);
        throw DbException.get(ErrorCode.ROW_NOT_FOUND_WHEN_DELETING_1, builder.toString());
      }
    } catch (SQLException e) {
      throw rocksTable.convertException(e);
    }


  }

  private final ConcurrentHashMap<Long, SessionLocal> rowLockOwners = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<SessionLocal, Set<Long>> sessionRowLocks = new ConcurrentHashMap<>();


  Row lockRow(SessionLocal session, Row row, int timeoutMillis) {
    TxnMap2 map = getTxnMap(session);
    long key = row.getKey();
    return lockRow(map, key, timeoutMillis);
  }

  private Row lockRow(TxnMap2 map, long key, int timeoutMillis) {
    try {
      return setRowKey((Row) map.lock(table.getId(), key, timeoutMillis), key);
    } catch (SQLException ex) {
      throw rocksTable.convertLockException(ex);
    }
  }

  @Override
  public Cursor find(SessionLocal session, SearchRow first, SearchRow last) {
    long min = extractPKFromRow(first, Long.MIN_VALUE);
    long max = extractPKFromRow(last, Long.MAX_VALUE);
    return find(session, min, max);
  }

  private long extractPKFromRow(SearchRow row, long defaultValue) {
    long result;
    if (row == null) {
      result = defaultValue;
    } else if (mainIndexColumn == SearchRow.ROWID_INDEX) {
      result = row.getKey() != 0 ? row.getKey() : defaultValue;
    } else {
      Value v = row.getValue(mainIndexColumn);
      if (v == null) {
        result = row.getKey() != 0 ? row.getKey() : defaultValue;
      } else if (v == ValueNull.INSTANCE) {
        result = 0L;
      } else {
        result = v.getLong();
      }
    }
    return result;
  }



  private Cursor find(SessionLocal session, Long first, Long last) {
    long startMillis = System.currentTimeMillis();
    RuntimeException failure = null;
    TxnMap2 map = getTxnMap(session);
    try {
      AdbSqlDistributedScanRuntime runtime =
          rocksTable.getSqlDistributedScanRuntime();
      if (runtime != null && runtime.isEnabled()) {
        return runtime.findRows(map.getTransaction(), map.getTabId(table.getId()),
            normalizeMin(first), normalizeMax(last));
      }
      if (first != null && last != null && first.longValue() == last.longValue()) {
        RowKey firstKey = RowKey.of(map.getTabId(table.getId()), first);
        if (map.canSkipAppendUniqueCheck(firstKey)) {
          return new SingleRowCursor(null);
        }
        RowValue rowValue = visiblePointRow(map, firstKey);
        if (rowValue == null || rowValue.deleted || rowValue.payload == null || rowValue.payload.length == 0) {
          decodedRowCache.remove(firstKey);
          return new SingleRowCursor(null);
        }
        Row row = decodePointRow(firstKey, first, rowValue);
        return new SingleRowCursor(setRowKey(row, first));
      }

      RowPrefix rowPrefix = RowPrefix.of(map.getTabId(table.getId()));
      return new RocksStoreCursor(map.entryIterator(rowPrefix, first, last));
    } catch (SQLException e) {
      failure = rocksTable.convertException(e);
      throw failure;
    } catch (RuntimeException e) {
      failure = e;
      throw e;
    } finally {
      rocksTable.recordSqlDiagnostic("SELECT", "PRIMARY_FIND", startMillis,
          failure);
    }
  }

  private static Long normalizeMin(Long first) {
    return first == null || first == Long.MIN_VALUE ? null : first;
  }

  private static Long normalizeMax(Long last) {
    return last == null || last == Long.MAX_VALUE ? null : last;
  }



  @Override
  public AdbTable getTable() {
    return rocksTable;
  }

  @Override
  public Row getRow(SessionLocal session, long key) {
    TxnMap2 map = getTxnMap(session);
    try {
      RowKey firstKey = RowKey.of(map.getTabId(table.getId()), key);
      RowValue rowValue = visiblePointRow(map, firstKey);
      if (rowValue == null || rowValue.deleted || rowValue.payload == null || rowValue.payload.length == 0) {
        decodedRowCache.remove(firstKey);
        return null;
      }
      return decodePointRow(firstKey, key, rowValue);
    } catch (SQLException e) {
      throw rocksTable.convertException(e);
    }
  }

  @Override
  public double getCost(SessionLocal session, int[] masks,
                        TableFilter[] filters, int filter, SortOrder sortOrder,
                        AllColumnsForPlan allColumnsSet) {
    try {
      return 10 * getCostRangeIndex(masks, getRowCount(session),
          filters, filter, sortOrder, true, allColumnsSet);
    } catch (MVStoreException e) {
      throw DbException.get(ErrorCode.OBJECT_CLOSED, e);
    }
  }

  @Override
  public int getColumnIndex(Column col) {
    // can not use this index - use the delegate index instead
    return col.getColumnId();//SearchRow.ROWID_INDEX;
  }

  @Override
  public boolean isFirstColumn(Column column) {
    return false;
  }

  @Override
  public void remove(SessionLocal session) {
    TxnMap2 map = getTxnMap(session);
    decodedRowCache.clear();
    //map.clear(KeyWrapper.tablePrefix(table.getId()));
  }

  @Override
  public void truncate(SessionLocal session) {
    if (rocksTable.getContainsLargeObject()) {
      database.getLobStorage().removeAllForTable(table.getId());
    }
    TxnMap2 map = getTxnMap(session);
    decodedRowCache.clear();
    //map.clear(KeyWrapper.tablePrefix(table.getId()));
  }

  @Override
  public boolean canGetFirstOrLast() {
    return true;
  }

  @Override
  public Cursor findFirstOrLast(SessionLocal session, boolean first) {
    TxnMap2 map = getTxnMap(session);
    RowPrefix tablePrefix = RowPrefix.of(map.getTabId(table.getId()));
    try {
      RowValue  rowValue = first ? map.first(tablePrefix) : map.last(tablePrefix);
      Row row = null;
      if(rowValue!=null&&!rowValue.deleted&&rowValue.payload!=null){
        row = RowCodec.decode(rowValue.rowKey, rowValue.payload);
      }
      return new SingleRowCursor(row != null ? setRowKey(row, row.getKey()) : null);
    } catch (SQLException e) {
      throw rocksTable.convertException(e);
    }
  }

  @Override
  public boolean needRebuild() {
    return false;
  }

  @Override
  public long getRowCount(SessionLocal session) {
    return table.getRowCount(session);
  }



  @Override
  public long getRowCountApproximation(SessionLocal session) {
    return table.getRowCount(session);
  }

  @Override
  public long getDiskSpaceUsed() {
    throw DbException.getUnsupportedException("getDiskSpaceUsed");
    //return 0;
  }


  @Override
  public void addRowsToBuffer(SessionLocal session, List<Row> rows) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void addBufferedRows(SessionLocal session) {
    throw new UnsupportedOperationException();
  }


  @Override
  public boolean isRowIdIndex() {
    return true;
  }



  @Override
  public TxnMap2 getTxnMap(SessionLocal session) {
    return rocksTable.getTxnMap(session);
  }

  private static Row setRowKey(Row row, long key) {
    if (row != null && row.getKey() == 0) {
      row.setKey(key);
    }
    return row;
  }

  private RowValue visiblePointRow(TxnMap2 map, RowKey rowKey)
      throws SQLException {
    if (!detailedSqlDiagnostics()) {
      return map.getVisible(rowKey);
    }
    long started = System.nanoTime();
    try {
      return map.getVisible(rowKey);
    } finally {
      rocksTable.recordSqlPhase("ADB_PRIMARY_FIND_VISIBLE_ROW",
          System.nanoTime() - started);
    }
  }

  private Row decodePointRow(RowKey rowKey, long rowId, RowValue rowValue) {
    if (!detailedSqlDiagnostics()) {
      CachedDecodedRow cached = decodedRowCache.get(rowKey);
      if (cached != null && cached.commitTs == rowValue.commitTs) {
        return rowFromValues(rowId, cached.values);
      }
      Value[] values = RowCodec.decodeRowValues(rowValue.payload);
      cacheDecodedValues(rowKey, rowValue.commitTs, values);
      return rowFromValues(rowId, values);
    }
    long started = System.nanoTime();
    CachedDecodedRow cached = decodedRowCache.get(rowKey);
    if (cached != null && cached.commitTs == rowValue.commitTs) {
      try {
        return buildPointRow(rowId, cached.values);
      } finally {
        rocksTable.recordSqlPhase("ADB_PRIMARY_FIND_ROW_CACHE_HIT",
            System.nanoTime() - started);
      }
    }
    try {
      Value[] values = decodePointValues(rowValue);
      cacheDecodedValues(rowKey, rowValue.commitTs, values);
      return buildPointRow(rowId, values);
    } finally {
      rocksTable.recordSqlPhase("ADB_PRIMARY_FIND_ROW_CACHE_MISS",
          System.nanoTime() - started);
    }
  }

  private Value[] decodePointValues(RowValue rowValue) {
    long started = System.nanoTime();
    try {
      return RowCodec.decodeRowValues(rowValue.payload);
    } finally {
      rocksTable.recordSqlPhase("ADB_PRIMARY_FIND_ROW_DECODE",
          System.nanoTime() - started);
    }
  }

  private Row buildPointRow(long rowId, Value[] values) {
    long started = System.nanoTime();
    try {
      return rowFromValues(rowId, values);
    } finally {
      rocksTable.recordSqlPhase("ADB_PRIMARY_FIND_ROW_BUILD",
          System.nanoTime() - started);
    }
  }

  private static Row rowFromValues(long rowId, Value[] values) {
    DefaultRow row = new DefaultRow(Arrays.copyOf(values, values.length));
    row.setKey(rowId);
    return row;
  }

  private static boolean detailedSqlDiagnostics() {
    return Boolean.getBoolean("vexra.adb.sql.diagnostic.detail");
  }

  private void cacheDecodedValues(RowKey rowKey, long commitTs, Value[] values) {
    if (DECODED_ROW_CACHE_LIMIT <= 0) {
      return;
    }
    if (decodedRowCache.size() >= DECODED_ROW_CACHE_LIMIT) {
      decodedRowCache.clear();
    }
    decodedRowCache.put(rowKey, CachedDecodedRow.of(commitTs, values));
  }

  private static final class CachedDecodedRow {
    private final long commitTs;
    private final Value[] values;

    private CachedDecodedRow(long commitTs, Value[] values) {
      this.commitTs = commitTs;
      this.values = values;
    }

    private static CachedDecodedRow of(long commitTs, Value[] values) {
      return new CachedDecodedRow(commitTs,
          Arrays.copyOf(values, values.length));
    }
  }

  /**
   * A cursor.
   */
  static final class RocksStoreCursor implements Cursor, AutoCloseable {

    private TableScanCursor it;
    private RowValue current;
    private Row row;

    public RocksStoreCursor(TableScanCursor it) {
      this.it = it;
    }

    @Override
    public Row get() {
      if (row == null) {
        if (current != null) {
          row = RowCodec.decode(current.rowKey, current.payload);
        }
      }
      return row;
    }

    @Override
    public SearchRow getSearchRow() {
      return get();
    }

    @Override
    public boolean next() {
      row = null;
      current = null;
      if (!it.next()) {
        return false;
      }
      current = it.get();
      return current != null;
    }

    @Override
    public boolean previous() {
      throw DbException.getUnsupportedException("previous");
    }

    @Override
    public synchronized void close() throws Exception {
      if(it!=null) {
        it.close();
        it = null;
      }
    }
  }
}
