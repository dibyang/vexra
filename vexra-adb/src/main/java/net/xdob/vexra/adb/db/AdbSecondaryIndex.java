package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.IndexKey;
import net.xdob.vexra.adb.key.IndexPrefix;
import net.xdob.vexra.adb.key.IndexPrefix2;
import net.xdob.vexra.adb.key.TabId;
import org.h2.api.ErrorCode;
import org.h2.command.query.AllColumnsForPlan;
import org.h2.engine.Database;
import org.h2.engine.SessionLocal;
import org.h2.index.Cursor;
import org.h2.index.IndexType;
import org.h2.index.SingleRowCursor;
import org.h2.message.DbException;
import org.h2.mvstore.MVStoreException;
import org.h2.mvstore.type.DataType;
import org.h2.result.*;
import org.h2.table.IndexColumn;
import org.h2.table.TableFilter;
import org.h2.value.Value;
import org.h2.value.ValueNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.*;

/**
 * An index stored in a RocksStore.
 */
public final class AdbSecondaryIndex extends AdbIndex<SearchRow, Value> {
  static Logger LOG = LoggerFactory.getLogger(AdbSecondaryIndex.class);

  private final AdbTable adbTable;
  private final List<IndexKey> buffer = new LinkedList<>();
  public AdbSecondaryIndex(Database db, AdbTable table, int id, String indexName,
                           IndexColumn[] columns, int uniqueColumnCount, IndexType indexType) {
    super(table, id, indexName, columns, uniqueColumnCount, indexType);
    this.adbTable = table;
    if (!database.isStarting()) {
      checkIndexColumnTypes(columns);
    }
  }

  private boolean isUnique(){
    return indexType.isPrimaryKey()||indexType.isUnique();
  }


  @Override
  public void addRowsToBuffer(SessionLocal session, List<Row> rows) {
    for (Row row : rows) {
      buffer.add(indexKey(session, row));
    }
  }

  /**
   * 为 bulk insert 构造并登记当前二级索引的事务内写入。
   *
   * <p>该方法先完成唯一索引的外部冲突和批内冲突检查，再把 index key 写入当前
   * ADB 事务 write set。它不直接写 committed version，因此会随用户事务一起
   * commit/rollback。</p>
   *
   * @param session 当前 H2 session
   * @param rows 已完成主键准备的待插入行
   */
  void bulkInsertRows(SessionLocal session, List<Row> rows) {
    if (rows.isEmpty()) {
      return;
    }
    TxnMap2 map = getTxnMap(session);
    List<IndexKey> indexKeys = new ArrayList<>(rows.size());
    Set<ByteArrayKey> uniqueKeys = isUnique() ? new HashSet<>() : null;
    try {
      for (Row row : rows) {
        if (needsUniqueCheck(row)) {
          byte[] uniqueKey = uniqueKey(row);
          if (!uniqueKeys.add(new ByteArrayKey(uniqueKey))) {
            throw getDuplicateKeyException(row.toString());
          }
          boolean repeatableRead =
              !session.getTransaction().allowNonRepeatableRead();
          checkUnique(repeatableRead, map, row, row.getKey());
        }
        indexKeys.add(indexKey(map, row));
      }
      map.putIndexKeys(indexKeys);
    } catch (SQLException e) {
      throw adbTable.convertException(e);
    }
  }

  private static final class Source {

    private final Iterator<SearchRow> iterator;

    SearchRow currentRowData;

    public Source(Iterator<SearchRow> iterator) {
      assert iterator.hasNext();
      this.iterator = iterator;
      this.currentRowData = iterator.next();
    }

    public boolean hasNext() {
      boolean result = iterator.hasNext();
      if (result) {
        currentRowData = iterator.next();
      }
      return result;
    }

    public SearchRow next() {
      return currentRowData;
    }

    static final class Comparator implements java.util.Comparator<AdbSecondaryIndex.Source> {

      private final DataType<SearchRow> type;

      public Comparator(DataType<SearchRow> type) {
        this.type = type;
      }

      @Override
      public int compare(AdbSecondaryIndex.Source one, AdbSecondaryIndex.Source two) {
        return type.compare(one.currentRowData, two.currentRowData);
      }
    }
  }

  @Override
  public void addBufferedRows(SessionLocal session) {
    try {
      TxnMap2 map = getTxnMap(session);
      IndexPrefix indexPrefix = IndexPrefix.of(map.getTabId(table.getId()), this.getId());
      map.addIndexBatch(indexPrefix, buffer);
      buffer.clear();
    } catch (SQLException e) {
      throw adbTable.convertException(e);
    }
  }

  @Override
  public TxnMap2 getTxnMap(SessionLocal session) {
    return adbTable.getTxnMap(session);
  }

  @Override
  public void close(SessionLocal session) {
    // ok
  }


  @Override
  public void add(SessionLocal session, Row row) {

    TxnMap2 map = getTxnMap(session);

    try {
      boolean checkRequired = needsUniqueCheck(row);
      if (checkRequired) {
        boolean repeatableRead = !session.getTransaction().allowNonRepeatableRead();
        checkUnique(repeatableRead, map, row, row.getKey());
      }
      IndexKey indexKey = indexKey(map, row);
      map.put(indexKey, ValueNull.INSTANCE);

    } catch (SQLException e) {
      throw adbTable.convertException(e);
    }
  }

  private IndexKey indexKey(SessionLocal session, Row row) {
    return indexKey(getTxnMap(session), row);
  }

  private IndexKey indexKey(TxnMap2 map, Row row) {
    byte[] encode = SearchRowCodec.encode(row, indexColumns, false);
    return IndexKey.of(map.getTabId(table.getId()), this.getId(), encode,
        row.getKey());
  }

  private byte[] uniqueKey(SearchRow row) {
    RowFactory uniqueRowFactory = getUniqueRowFactory();
    SearchRow from = uniqueRowFactory.createRow();
    from.copyFrom(row);
    return SearchRowCodec.encode(from, indexColumns, false);
  }


  private void checkUnique(boolean repeatableRead, TxnMap2 map, SearchRow row, long newKey) throws SQLException {

    RowFactory uniqueRowFactory = getUniqueRowFactory();
    SearchRow from = uniqueRowFactory.createRow();
    from.copyFrom(row);

    if (repeatableRead) {
      //涓轰簡淇濊瘉鍙噸澶嶈锛岄渶瑕佸璇彞鎴栦簨鍔″紑濮嬫椂鑾峰彇鐨勫揩鐓ц繘琛岄澶栨鏌ワ紝
      // 鍥犱负鍗充娇涔嬪悗璇ラ敭宸茶鍙︿竴涓紙鍙兘宸叉彁浜ょ殑锛変簨鍔″垹闄わ紝涔熷繀椤昏€冭檻閿殑瀛樺湪鎬с€傛殏涓嶆敮鎸?
//      TransactionMap.TMIterator<SearchRow, Value, SearchRow> it = map.keyIterator(from, to);
//      for (SearchRow k; (k = it.fetchNext()) != null;) {
//        if (newKey != k.getKey() && !map.isDeletedByCurrentTransaction(k)) {
//          throw getDuplicateKeyException(k.toString());
//        }
//      }
    }
    byte[] encoded = SearchRowCodec.encode(from, indexColumns, false);

    IndexPrefix prefix = IndexPrefix.of(map.getTabId(table.getId()), this.getId());
    IndexKey fromKey = IndexKey.of(map.getTabId(table.getId()), this.getId(), encoded, Long.MIN_VALUE);
    IndexKey toKey   = IndexKey.of(map.getTabId(table.getId()), this.getId(), encoded, Long.MAX_VALUE);

    try (IndexScanCursor it = map.indexScanIterator(prefix, fromKey, toKey)) {
      while (it.next()) {
        IndexKey k = it.get();

        if (k.getRowId() == newKey) {
          continue;
        }

        UniqueCheckResult result = map.checkUniqueConflict(k, newKey);

        switch (result) {
          case DUPLICATE:
            throw getDuplicateKeyException(row.toString());
          case CONCURRENT_CONFLICT:
            throw DbException.get(ErrorCode.CONCURRENT_UPDATE_1, table.getName());
          case IGNORE:
            break;
        }
      }
    }
  }


  @Override
  public void remove(SessionLocal session, Row row) {
    SearchRow searchRow = convertToKey(row);
    TxnMap2 map = getTxnMap(session);
    try {
      byte[] encode = SearchRowCodec.encode(row, indexColumns, false);
      IndexKey indexKey = IndexKey.of(map.getTabId(table.getId()), this.getId(), encode, row.getKey());
      if (map.delete(indexKey) == null) {
        StringBuilder builder = new StringBuilder();
        getSQL(builder, TRACE_SQL_FLAGS).append(": ").append(row.getKey());
        throw DbException.get(ErrorCode.ROW_NOT_FOUND_WHEN_DELETING_1, builder.toString());
      }
    } catch (SQLException e) {
      throw adbTable.convertException(e);
    }
  }

  @Override
  public void update(SessionLocal session, Row oldRow, Row newRow) {
    SearchRow searchRowOld = convertToKey(oldRow);
    SearchRow searchRowNew = convertToKey(newRow);
    if (!rowsAreEqual(searchRowOld, searchRowNew)) {
      super.update(session, oldRow, newRow);
    }
  }

  private boolean rowsAreEqual(SearchRow rowOne, SearchRow rowTwo) {
    if (rowOne == rowTwo) {
      return true;
    }
    for (int index : columnIds) {
      Value v1 = rowOne.getValue(index);
      Value v2 = rowTwo.getValue(index);
      if (!Objects.equals(v1, v2)) {
        return false;
      }
    }
    return rowOne.getKey() == rowTwo.getKey();
  }

  @Override
  public Cursor find(SessionLocal session, SearchRow first, SearchRow last) {
    return find(session, first, false, last);
  }
  boolean isFullKey(SearchRow row, IndexColumn[] indexColumns) {
    for (IndexColumn col : indexColumns) {
      int idx = col.column.getColumnId();
      if (idx < 0 || idx >= row.getColumnCount()) return false;
      Value v = row.getValue(idx);
      if (v == null || v == ValueNull.INSTANCE) return false;
    }
    return true;
  }

  private synchronized Cursor find(SessionLocal session, SearchRow first, boolean bigger, SearchRow last) {
    long startMillis = System.currentTimeMillis();
    RuntimeException failure = null;
    TxnMap2 map = getTxnMap(session);
    try {
      TabId tabId = map.getTabId(table.getId());

      IndexPrefix prefix = IndexPrefix.of(tabId, this.getId());
      IndexPrefix2 minKey = null;
      IndexPrefix2 maxKey = null;

      if (first != null) {
        byte[] firstEncoded = SearchRowCodec.encode(convertToKey(first), indexColumns, false);
        IndexPrefix2 firstKey = IndexPrefix2.of(tabId, getId(), firstEncoded);

        if (bigger) {
          minKey = IndexPrefix2.fromBytes(KeyCodec.prefixEnd(firstKey.toBytes()));
        } else {
          minKey = firstKey;
        }
      }

      if (last != null) {
        byte[] lastEncoded = SearchRowCodec.encode(convertToKey(last), indexColumns, false);
        IndexPrefix2 lastKey = IndexPrefix2.of(tabId, getId(), lastEncoded);

        if (first != null && rowsAreEqual(first, last)) {
          maxKey = IndexPrefix2.fromBytes(KeyCodec.prefixEnd(lastKey.toBytes()));
          if (!bigger) {
            minKey = lastKey;
          }
        } else {
          maxKey = IndexPrefix2.fromBytes(KeyCodec.prefixEnd(lastKey.toBytes()));
        }
      }

      IndexScanCursor iterator = map.indexScanIterator(prefix, minKey, maxKey);
      return new RocksStoreCursor(session, iterator, adbTable);
    } catch (RuntimeException e) {
      failure = e;
      throw e;
    } finally {
      adbTable.recordSqlDiagnostic("SELECT", "SECONDARY_FIND", startMillis,
          failure);
    }
  }

  private static final class ByteArrayKey {
    private final byte[] value;

    ByteArrayKey(byte[] value) {
      this.value = value.clone();
    }

    @Override
    public boolean equals(Object obj) {
      return obj instanceof ByteArrayKey
          && Arrays.equals(value, ((ByteArrayKey) obj).value);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(value);
    }
  }


  private SearchRow convertToKey(SearchRow r) {
    if (r == null) {
      return null;
    }

    SearchRow row = getRowFactory().createRow();
    row.copyFrom(r);
    return row;
  }

  @Override
  public AdbTable getTable() {
    return adbTable;
  }

  @Override
  public double getCost(SessionLocal session, int[] masks,
                        TableFilter[] filters, int filter, SortOrder sortOrder,
                        AllColumnsForPlan allColumnsSet) {
    try {
      return 10 * getCostRangeIndex(masks, getRowCount(session),
          filters, filter, sortOrder, false, allColumnsSet);
    } catch (MVStoreException e) {
      throw DbException.get(ErrorCode.OBJECT_CLOSED, e);
    }
  }


  @Override
  public void remove(SessionLocal session) {
    TxnMap2 map = adbTable.getTxnMap(session);
    //map.clear(getPkOrIndexPrefix());
  }


  @Override
  public void truncate(SessionLocal session) {
    TxnMap2 map = adbTable.getTxnMap(session);
//    try {
//      map.truncate(getPkOrIndexPrefix());
//    } catch (SQLException e) {
//      throw rocksTable.convertException(e);
//    }
  }

  @Override
  public boolean canGetFirstOrLast() {
    return true;
  }

  @Override
  public Cursor findFirstOrLast(SessionLocal session, boolean first) {
    TxnMap2 map = adbTable.getTxnMap(session);
    IndexPrefix prefix = IndexPrefix.of(map.getTabId(table.getId()), this.getId());
    try(IndexScanCursor iterator = map.indexScanIterator(prefix, null, null)) {
      while (iterator.next()) {
        IndexKey next = iterator.get();
        long rowId = next.getRowId();

        Row row = table.getRow(session, rowId);
        if (row == null) continue;
        // 濡傛灉瑕佷弗鏍艰涔夛紝杩樿杩囨护 NULL
        if (row.getValue(columnIds[0]) == ValueNull.INSTANCE) {
          continue;
        }
        return new SingleRowCursor(row);
      }
    } catch (Exception e) {
      throw DbException.get(ErrorCode.GENERAL_ERROR_1, e, e.getMessage());
    }
    return new SingleRowCursor(null);
  }

  @Override
  public boolean needRebuild() {
    return true;
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
    // TODO estimate disk space usage
    return 0;
  }

  @Override
  public boolean canFindNext() {
    return true;
  }

  @Override
  public Cursor findNext(SessionLocal session, SearchRow higherThan, SearchRow last) {
    return find(session, higherThan, true, last);
  }


  /**
   * A cursor.
   */
  static final class RocksStoreCursor implements Cursor, AutoCloseable {

    private final SessionLocal session;
    private IndexScanCursor it;
    private final AdbTable mvTable;
    private IndexKey current;
    private Row row;

    RocksStoreCursor(SessionLocal session, IndexScanCursor it, AdbTable mvTable) {
      this.session = session;
      this.it = it;
      this.mvTable = mvTable;
    }

    @Override
    public Row get() {
      if (row == null) {
        SearchRow r = getSearchRow();
        if (r != null) {
          row = mvTable.getRow(session, r.getKey());
        }
      }
      return row;
    }

    @Override
    public SearchRow getSearchRow() {
      if (current == null) return null;
      SimpleRowValue r = new SimpleRowValue(0);
      r.setKey(current.getRowId());
      return r;
    }

    @Override
    public boolean next() {
      try {
        if (!it.next()) {
          return false;
        }
        current = it.get();
        row = null;
        return current != null;
      } catch (Exception e) {
        throw DbException.get(ErrorCode.GENERAL_ERROR_1, e, e.getMessage());
      }
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
