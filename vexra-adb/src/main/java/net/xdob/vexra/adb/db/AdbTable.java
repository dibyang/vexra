package net.xdob.vexra.adb.db;


import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.adb.h2plugin.AdbTransactionRegistry;
import net.xdob.vexra.adb.key.IndexBuildState;
import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.TabId;
import org.h2.api.DatabaseEventListener;
import org.h2.api.ErrorCode;
import org.h2.command.ddl.CreateTableData;
import org.h2.constraint.Constraint;
import org.h2.constraint.ConstraintReferential;
import org.h2.engine.Constants;
import org.h2.engine.Database;
import org.h2.engine.SessionLocal;
import org.h2.engine.SysProperties;
import org.h2.index.Cursor;
import org.h2.index.Index;
import org.h2.index.IndexType;
import org.h2.message.DbException;
import org.h2.message.Trace;
import org.h2.mode.DefaultNullOrdering;
import org.h2.mvstore.db.Store;
import org.h2.mvstore.tx.Transaction;
import org.h2.mvstore.tx.TransactionStore;
import org.h2.result.Row;
import org.h2.result.SearchRow;
import org.h2.result.SortOrder;
import org.h2.table.Column;
import org.h2.table.IndexColumn;
import org.h2.table.Table;
import org.h2.table.TableBase;
import org.h2.table.TableType;
import org.h2.util.DebuggingThreadLocal;
import org.h2.util.Utils;
import org.h2.value.DataType;
import org.h2.value.TypeInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A table stored in a MVStore.
 */
public class AdbTable extends TableBase {
  static final Logger LOG = LoggerFactory.getLogger(AdbTable.class);
  /**
   * The table name this thread is waiting to lock.
   */
  public static final DebuggingThreadLocal<String> WAITING_FOR_LOCK;

  /**
   * The table names this thread has exclusively locked.
   */
  public static final DebuggingThreadLocal<ArrayList<String>> EXCLUSIVE_LOCKS;

  /**
   * The tables names this thread has a shared lock on.
   */
  public static final DebuggingThreadLocal<ArrayList<String>> SHARED_LOCKS;

  /**
   * The type of trace lock events
   */
  private enum TraceLockEvent{

    TRACE_LOCK_OK("ok"),
    TRACE_LOCK_WAITING_FOR("waiting for"),
    TRACE_LOCK_REQUESTING_FOR("requesting for"),
    TRACE_LOCK_TIMEOUT_AFTER("timeout after "),
    TRACE_LOCK_UNLOCK("unlock"),
    TRACE_LOCK_ADDED_FOR("added for"),
    TRACE_LOCK_ADD_UPGRADED_FOR("add (upgraded) for ");

    private final String eventText;

    TraceLockEvent(String eventText) {
      this.eventText = eventText;
    }

    public String getEventText() {
      return eventText;
    }
  }
  private static final String NO_EXTRA_INFO = "";

  static {
    if (SysProperties.THREAD_DEADLOCK_DETECTOR) {
      WAITING_FOR_LOCK = new DebuggingThreadLocal<>();
      EXCLUSIVE_LOCKS = new DebuggingThreadLocal<>();
      SHARED_LOCKS = new DebuggingThreadLocal<>();
    } else {
      WAITING_FOR_LOCK = null;
      EXCLUSIVE_LOCKS = null;
      SHARED_LOCKS = null;
    }
  }

  /**
   * Whether the table contains a CLOB or BLOB.
   */
  private final boolean containsLargeObject;

  /**
   * The session (if any) that has exclusively locked this table.
   */
  private volatile SessionLocal lockExclusiveSession;

  /**
   * The set of sessions (if any) that have a shared lock on the table. Here
   * we are using using a ConcurrentHashMap as a set, as there is no
   * ConcurrentHashSet.
   */
  private final ConcurrentHashMap<SessionLocal, SessionLocal> lockSharedSessions = new ConcurrentHashMap<>();

  private Column rowIdColumn;

  private final AdbPrimaryIndex primaryIndex;
  private final ArrayList<Index> indexes = Utils.newSmallArrayList();
  private final AtomicLong lastModificationId = new AtomicLong();

  /**
   * The queue of sessions waiting to lock the table. It is a FIFO queue to
   * prevent starvation, since Java's synchronized locking is biased.
   */
  private final ArrayDeque<SessionLocal> waitingSessions = new ArrayDeque<>();
  private final Trace traceLock;
  private final AtomicInteger changesUntilAnalyze;
  private int nextAnalyze;

  private final Store store;
  private final TransactionStore transactionStore;
  private final DbStore dbStore;
  private final TxnManager txnManager;
  private final KeyGenerator keyGenerator;

  public AdbTable(CreateTableData data, Store store, DbStore dbStore, TxnManager txnManager) {
    super(data);
    this.dbStore = dbStore;
    this.txnManager = txnManager;
    this.keyGenerator = new KeyGenerator(dbStore, getId());
    this.isHidden = data.isHidden;
    boolean b = false;
    for (Column col : getColumns()) {
      if (DataType.isLargeObject(col.getType().getValueType())) {
        b = true;
        break;
      }
    }
    containsLargeObject = b;
    nextAnalyze = database.getSettings().analyzeAuto;
    changesUntilAnalyze = nextAnalyze <= 0 ? null : new AtomicInteger(nextAnalyze);
    this.store = store;
    this.transactionStore = store.getTransactionStore();
    traceLock = database.getTrace(Trace.LOCK);

    primaryIndex = new AdbPrimaryIndex(database, this, getId(),
        IndexColumn.wrap(getColumns()), IndexType.createScan(true));

    indexes.add(primaryIndex);
    prewarmRowCountCache();
  }

  public DbStore getRocksStore() {
    return dbStore;
  }

  public long nextKey(){
    try {
      return keyGenerator.nextKey();
    } catch (SQLException e) {
      LOG.warn("nextKey error", e);
      throw convertException(e);
    }
  }


  public TxnMap2 getTxnMap(SessionLocal sessionLocal){
   return AdbTransactionRegistry.getOrCreate(sessionLocal, txnManager);
  }

  private void prewarmRowCountCache() {
    if (!rowCountPrewarmEnabled()) {
      return;
    }
    try {
      txnManager.prewarmRowCountCache(TabId.of(getId(), 0L));
    } catch (SQLException e) {
      LOG.warn("prewarm row-count cache failed, table={}", getName(), e);
    }
  }

  private static boolean rowCountPrewarmEnabled() {
    return Boolean.parseBoolean(System.getProperty(
        "vexra.adb.rowCount.prewarm", "true"));
  }

  /**
   * 返回当前表绑定的 SQL 分布式 scan runtime。
   *
   * @return runtime；未启用时为 null
   */
  public AdbSqlDistributedScanRuntime getSqlDistributedScanRuntime() {
    return txnManager.getSqlDistributedScanRuntime();
  }

  /**
   * 返回当前表绑定的 SQL 分布式写入 runtime。
   *
   * @return runtime；未启用时为 null
   */
  public AdbSqlDistributedWriteRuntime getSqlDistributedWriteRuntime() {
    return txnManager.getSqlDistributedWriteRuntime();
  }

  /**
   * 记录一条真实 ADB table engine 操作的 SQL 诊断摘要。
   *
   * <p>当前 h2db 插件点位于 table/index 层，无法直接拿到原始 SQL 文本；这里用稳定的
   * ADB_TABLE_* 摘要保留 SQL 类型、表名、耗时和异常，先满足生产排障需要，后续若 h2db
   * 暴露 statement listener 可再替换为原始 SQL 文本。</p>
   *
   * @param sqlType SQL 类型
   * @param operation ADB 表操作名
   * @param startMillis 操作开始时间
   * @param failure 失败异常；成功时为 null
   */
  public void recordSqlDiagnostic(String sqlType, String operation, long startMillis,
      Throwable failure) {
    if (txnManager.getSqlDiagnosticRecorder() == null) {
      return;
    }
    long now = System.currentTimeMillis();
    long latencyMillis = Math.max(0L, now - startMillis);
    String summary = "ADB_TABLE_" + operation + " " + getName();
    AdbSqlDiagnosticEvent event = failure == null
        ? AdbSqlDiagnosticEvent.success(now, sqlType, getName(), summary,
            latencyMillis)
        : AdbSqlDiagnosticEvent.failure(now, sqlType, getName(), summary,
            latencyMillis, failure);
    txnManager.recordSqlDiagnostic(event);
    txnManager.recordSqlPhase(summary, latencyMillis * 1_000_000L);
  }

  /**
   * 记录 SQL/table-engine 关键阶段耗时。
   *
   * @param phase 阶段名
   * @param latencyNanos 阶段耗时，纳秒
   */
  public void recordSqlPhase(String phase, long latencyNanos) {
    txnManager.recordSqlPhase(phase, latencyNanos);
  }

  @Override
  public long getRowCount(SessionLocal session){
    long startMillis = System.currentTimeMillis();
    RuntimeException failure = null;
    try {
      TxnMap2 map = getTxnMap(session);
      return map.getRowCount(getId());
    } catch (SQLException e) {
      failure = convertException(e);
      throw failure;
    } catch (RuntimeException e) {
      failure = e;
      throw e;
    } finally {
      recordSqlDiagnostic("SELECT", "ROW_COUNT", startMillis, failure);
    }
  }


  @Override
  public boolean lock(SessionLocal session, int lockType) {
    if (database.getLockMode() == Constants.LOCK_MODE_OFF) {
      session.registerTableAsUpdated(this);
      return false;
    }
    if (lockType == Table.READ_LOCK && lockExclusiveSession == null) {
      return false;
    }
    if (lockExclusiveSession == session) {
      return true;
    }
    if (lockType != Table.EXCLUSIVE_LOCK && lockSharedSessions.containsKey(session)) {
      return true;
    }
    synchronized (this) {
      if (lockType != Table.EXCLUSIVE_LOCK && lockSharedSessions.containsKey(session)) {
        return true;
      }
      session.setWaitForLock(this, Thread.currentThread());
      if (SysProperties.THREAD_DEADLOCK_DETECTOR) {
        WAITING_FOR_LOCK.set(getName());
      }
      waitingSessions.addLast(session);
      try {
        doLock1(session, lockType);
      } finally {
        session.setWaitForLock(null, null);
        if (SysProperties.THREAD_DEADLOCK_DETECTOR) {
          WAITING_FOR_LOCK.remove();
        }
        waitingSessions.remove(session);
      }
    }
    return false;
  }

  private void doLock1(SessionLocal session, int lockType) {
    traceLock(session, lockType, TraceLockEvent.TRACE_LOCK_REQUESTING_FOR, NO_EXTRA_INFO);
    // don't get the current time unless necessary
    long max = 0L;
    boolean checkDeadlock = false;
    while (true) {
      // if I'm the next one in the queue
      if (waitingSessions.getFirst() == session && lockExclusiveSession == null) {
        if (doLock2(session, lockType)) {
          return;
        }
      }
      if (checkDeadlock) {
        ArrayList<SessionLocal> sessions = checkDeadlock(session, null, null);
        if (sessions != null) {
          throw DbException.get(ErrorCode.DEADLOCK_1,
              getDeadlockDetails(sessions, lockType));
        }
      } else {
        // check for deadlocks from now on
        checkDeadlock = true;
      }
      long now = System.nanoTime();
      if (max == 0L) {
        // try at least one more time
        max = Utils.nanoTimePlusMillis(now, session.getLockTimeout());
      } else if (now - max >= 0L) {
        traceLock(session, lockType,
            TraceLockEvent.TRACE_LOCK_TIMEOUT_AFTER, Integer.toString(session.getLockTimeout()));
        throw DbException.get(ErrorCode.LOCK_TIMEOUT_1, getName());
      }
      try {
        traceLock(session, lockType, TraceLockEvent.TRACE_LOCK_WAITING_FOR, NO_EXTRA_INFO);
        // don't wait too long so that deadlocks are detected early
        long sleep = Math.min(Constants.DEADLOCK_CHECK, (max - now) / 1_000_000L);
        if (sleep == 0) {
          sleep = 1;
        }
        wait(sleep);
      } catch (InterruptedException e) {
        // ignore
      }
    }
  }

  private boolean doLock2(SessionLocal session, int lockType) {
    switch (lockType) {
      case Table.EXCLUSIVE_LOCK:
        int size = lockSharedSessions.size();
        if (size == 0) {
          traceLock(session, lockType, TraceLockEvent.TRACE_LOCK_ADDED_FOR, NO_EXTRA_INFO);
          session.registerTableAsLocked(this);
        } else if (size == 1 && lockSharedSessions.containsKey(session)) {
          traceLock(session, lockType, TraceLockEvent.TRACE_LOCK_ADD_UPGRADED_FOR, NO_EXTRA_INFO);
        } else {
          return false;
        }
        lockExclusiveSession = session;
        if (SysProperties.THREAD_DEADLOCK_DETECTOR) {
          addLockToDebugList(EXCLUSIVE_LOCKS);
        }
        break;
      case Table.WRITE_LOCK:
        if (lockSharedSessions.putIfAbsent(session, session) == null) {
          traceLock(session, lockType, TraceLockEvent.TRACE_LOCK_OK, NO_EXTRA_INFO);
          session.registerTableAsLocked(this);
          if (SysProperties.THREAD_DEADLOCK_DETECTOR) {
            addLockToDebugList(SHARED_LOCKS);
          }
        }
    }
    return true;
  }

  private void addLockToDebugList(DebuggingThreadLocal<ArrayList<String>> locks) {
    ArrayList<String> list = locks.get();
    if (list == null) {
      list = new ArrayList<>();
      locks.set(list);
    }
    list.add(getName());
  }

  private void traceLock(SessionLocal session, int lockType, TraceLockEvent eventEnum, String extraInfo) {
    if (traceLock.isDebugEnabled()) {
      traceLock.debug("{0} {1} {2} {3} {4}", session.getId(),
          lockTypeToString(lockType), eventEnum.getEventText(),
          getName(), extraInfo);
    }
  }

  @Override
  public void unlock(SessionLocal s) {
    if (database != null) {
      int lockType;
      if (lockExclusiveSession == s) {
        lockType = Table.EXCLUSIVE_LOCK;
        lockSharedSessions.remove(s);
        lockExclusiveSession = null;
        if (SysProperties.THREAD_DEADLOCK_DETECTOR) {
          ArrayList<String> exclusiveLocks = EXCLUSIVE_LOCKS.get();
          if (exclusiveLocks != null) {
            exclusiveLocks.remove(getName());
          }
        }
      } else {
        lockType = lockSharedSessions.remove(s) != null ? Table.WRITE_LOCK : Table.READ_LOCK;
        if (SysProperties.THREAD_DEADLOCK_DETECTOR) {
          ArrayList<String> sharedLocks = SHARED_LOCKS.get();
          if (sharedLocks != null) {
            sharedLocks.remove(getName());
          }
        }
      }
      traceLock(s, lockType, TraceLockEvent.TRACE_LOCK_UNLOCK, NO_EXTRA_INFO);
      if (lockType != Table.READ_LOCK && !waitingSessions.isEmpty()) {
        synchronized (this) {
          notifyAll();
        }
      }
    }
  }

  @Override
  public void close(SessionLocal session) {
    AdbSqlDistributedScanRuntime runtime = getSqlDistributedScanRuntime();
    if (runtime != null) {
      try {
        runtime.close();
      } catch (Exception e) {
        throw convertException(new SQLException(
            "Failed to close ADB SQL distributed scan runtime", e));
      }
    }
    AdbSqlDistributedWriteRuntime writeRuntime =
        getSqlDistributedWriteRuntime();
    if (writeRuntime != null) {
      try {
        writeRuntime.close();
      } catch (Exception e) {
        throw convertException(new SQLException(
            "Failed to close ADB SQL distributed write runtime", e));
      }
    }
  }

  @Override
  public Row getRow(SessionLocal session, long key) {
    long startMillis = System.currentTimeMillis();
    RuntimeException failure = null;
    try {
      return primaryIndex.getRow(session, key);
    } catch (RuntimeException e) {
      failure = e;
      throw e;
    } finally {
      recordSqlDiagnostic("SELECT", "GET_ROW", startMillis, failure);
    }
  }



  @Override
  public Index addIndex(SessionLocal session, String indexName, int indexId, IndexColumn[] cols,
                        int uniqueColumnCount, IndexType indexType, boolean create, String indexComment) {
    cols = prepareColumns(database, cols, indexType);
    boolean isSessionTemporary = isTemporary() && !isGlobalTemporary();
    if (!isSessionTemporary) {
      database.lockMeta(session);
    }
    AdbIndex<?,?> index;
    int mainIndexColumn = primaryIndex.getMainIndexColumn() != SearchRow.ROWID_INDEX
        ? SearchRow.ROWID_INDEX : getMainIndexColumn(indexType, cols);
    if (database.isStarting()) {
      // if index does exists as a separate map it can't be a delegate
      if (transactionStore.hasMap("index." + indexId)) {
        // we can not reuse primary index
        mainIndexColumn = SearchRow.ROWID_INDEX;
      }
    } else if (primaryIndex.getRowCount(session) != 0) {
      mainIndexColumn = SearchRow.ROWID_INDEX;
    }

    if (mainIndexColumn != SearchRow.ROWID_INDEX) {
      primaryIndex.setMainIndexColumn(mainIndexColumn);
      index = new AdbDelegateIndex(this, indexId, indexName, primaryIndex,
          indexType);
    } else if (indexType.isSpatial()) {
      return null;
    } else {
      index = new AdbSecondaryIndex(session.getDatabase(), this, indexId,
          indexName, cols, uniqueColumnCount, indexType);
    }
    
    if (index.needRebuild() && needRebuildIndex(session, index)) {
      rebuildIndex(session, index, indexName);
    }
    index.setTemporary(isTemporary());
    if (index.getCreateSQL() != null) {
      index.setComment(indexComment);
      if (isSessionTemporary) {
        session.addLocalTempTableIndex(index);
      } else {
        database.addSchemaObject(session, index);
      }
    }
    indexes.add(index);
    setModified();
    return index;
  }

  boolean needRebuildIndex(SessionLocal session, Index index) {
    try {
      TxnMap2 txnMap = getTxnMap(session);
      IndexBuildState indexBuildState = txnMap.getIndexBuildState(getId(), index.getId());
      return IndexBuildState.BUILDING.equals(indexBuildState);
    } catch (SQLException e) {
      throw convertException(e);
    }
  }

  private void rebuildIndex(SessionLocal session, AdbIndex<?,?> index, String indexName) {
    try {
      if (!session.getDatabase().isPersistent()) {
        // in-memory
        rebuildIndexBuffered(session, index);
      } else {
        rebuildIndexBlockMerge(session, index);
      }
      try {
        TxnMap2 map = getTxnMap(session);
        map.setIndexBuildState(getId(), index.getId(), IndexBuildState.READY);
      } catch (SQLException e) {
        throw convertException(e);
      }
    } catch (DbException e) {
      getSchema().freeUniqueName(indexName);
      try {
        index.remove(session);
      } catch (DbException e2) {
        // this could happen, for example on failure in the storage
        // but if that is not the case it means
        // there is something wrong with the database
        trace.error(e2, "could not remove index");
        throw e2;
      }
      throw e;
    }
  }

  private void rebuildIndexBlockMerge(SessionLocal session, AdbIndex<?,?> index) {
    // Read entries in memory, sort them, write to a new map (in sorted
    // order); repeat (using a new map for every block of 1 MB) until all
    // record are read. Merge all maps to the target (using merge sort;
    // duplicates are detected in the target). For randomly ordered data,
    // this should use relatively few write operations.
    // A possible optimization is: change the buffer size from "row count"
    // to "amount of memory", and buffer index keys instead of rows.
    Index scan = getScanIndex(session);
    long remaining = scan.getRowCount(session);
    long total = remaining;
    Cursor cursor = scan.find(session, null, null);
    long i = 0;

    int bufferSize = (int) Math.min(total, database.getMaxMemoryRows() / 2);
    ArrayList<Row> buffer = new ArrayList<>(bufferSize);
    String n = getName() + ':' + index.getName();
    while (cursor.next()) {
      Row row = cursor.get();
      buffer.add(row);
      database.setProgress(DatabaseEventListener.STATE_CREATE_INDEX, n, i++, total);

      remaining--;
    }
    sortRows(buffer, index);
    index.addRowsToBuffer(session, buffer);
    buffer.clear();
    index.addBufferedRows(session);
    if (remaining != 0) {
      throw DbException.getInternalError("rowcount remaining=" + remaining + ' ' + getName());
    }
  }

  private void rebuildIndexBuffered(SessionLocal session, Index index) {
    Index scan = getScanIndex(session);
    long remaining = scan.getRowCount(session);
    long total = remaining;
    Cursor cursor = scan.find(session, null, null);
    long i = 0;
    int bufferSize = (int) Math.min(total, database.getMaxMemoryRows());
    ArrayList<Row> buffer = new ArrayList<>(bufferSize);
    String n = getName() + ':' + index.getName();
    while (cursor.next()) {
      Row row = cursor.get();
      buffer.add(row);
      database.setProgress(DatabaseEventListener.STATE_CREATE_INDEX, n, i++, total);
      if (buffer.size() >= bufferSize) {
        addRowsToIndex(session, buffer, index);
      }
      remaining--;
    }
    addRowsToIndex(session, buffer, index);
    if (remaining != 0) {
      throw DbException.getInternalError("rowcount remaining=" + remaining + ' ' + getName());
    }
  }

  @Override
  public void removeRow(SessionLocal session, Row row) {
    long startMillis = System.currentTimeMillis();
    RuntimeException failure = null;
    syncLastModificationIdWithDatabase();
    try {
      for (int i = indexes.size() - 1; i >= 0; i--) {
        Index index = indexes.get(i);
        index.remove(session, row);
      }
      if(session.getAutoCommit()){
        session.commit(false);
      }
    } catch (Throwable e) {
      failure = DbException.convert(e);
      throw failure;
    } finally {
      recordSqlDiagnostic("DELETE", "REMOVE_ROW", startMillis, failure);
    }
    analyzeIfRequired(session);
  }

  @Override
  public long truncate(SessionLocal session) {
    long startMillis = System.currentTimeMillis();
    RuntimeException failure = null;
    syncLastModificationIdWithDatabase();
    try {
      long result = getRowCountApproximation(session);
      TxnMap2 map = getTxnMap(session);
      map.truncate(getId());
      if (changesUntilAnalyze != null) {
        changesUntilAnalyze.set(nextAnalyze);
      }
      return result;
    } catch (RuntimeException e) {
      failure = e;
      throw e;
    } finally {
      recordSqlDiagnostic("TRUNCATE", "TRUNCATE", startMillis, failure);
    }
  }

  @Override
  public void addRow(SessionLocal session, Row row) {

    long startMillis = System.currentTimeMillis();
    RuntimeException failure = null;
    syncLastModificationIdWithDatabase();
    try {
      for (Index index : indexes) {
        index.add(session, row);
      }
    } catch (Throwable e) {
      failure = DbException.convert(e);
      throw failure;
    } finally {
      recordSqlDiagnostic("INSERT", "ADD_ROW", startMillis, failure);
    }
    analyzeIfRequired(session);
  }

  /**
   * 在当前 JDBC session 的 ADB 事务内批量追加写入 row。
   *
   * <p>该入口面向 benchmark 和后续 h2db bulk insert 扩展点：它绕过 H2
   * `Table.addRow -> Index.add` 的逐行调度，但仍复用 TxnManager/MVCC/RowCodec
   * 和 JDBC transaction event 的提交边界。第一版只允许没有二级索引的本地表使用；
   * 带二级索引或远端 region commit 的表必须继续走普通路径，避免索引不一致或绕过分布式提交。</p>
   *
   * @param session 当前 H2 session
   * @param rows 需要插入的 row；方法不会修改该列表
   * @return 成功写入当前事务的 row 数
   */
  public int bulkInsertAppendRows(SessionLocal session, List<Row> rows) {
    if (rows == null || rows.isEmpty()) {
      return 0;
    }
    if (getSqlDistributedWriteRuntime() != null
        || txnManager.getRegionCommitCoordinator() != null) {
      throw DbException.getUnsupportedException(
          "ADB bulk insert fast path is local-only");
    }
    long startMillis = System.currentTimeMillis();
    RuntimeException failure = null;
    syncLastModificationIdWithDatabase();
    TxnMap2 map = getTxnMap(session);
    long savepointId = System.nanoTime();
    map.setSavepoint(savepointId);
    int count = 0;
    try {
      List<BulkRowWrite> rowWrites = new ArrayList<>(rows.size());
      Set<Long> rowIds = new HashSet<>();
      for (Row row : rows) {
        prepareBulkRow(row);
        RowKey rowKey = RowKey.of(map.getTabId(getId()), row.getKey());
        if (!rowIds.add(row.getKey())) {
          throw duplicateKey(row.getKey(), null);
        }
        RowValue old = null;
        if (!map.canSkipAppendUniqueCheck(rowKey)) {
          old = map.getVisible(rowKey);
          if (isExistingRow(old)) {
            throw duplicateKey(row.getKey(), old);
          }
        }
        rowWrites.add(new BulkRowWrite(rowKey,
            rowValue(map.getTransaction().getTxnId(), row), old));
      }
      for (BulkRowWrite rowWrite : rowWrites) {
        map.putEncoded(rowWrite.rowKey, rowWrite.value, rowWrite.oldValue);
      }
      for (Index index : indexes) {
        if (index instanceof AdbSecondaryIndex) {
          ((AdbSecondaryIndex) index).bulkInsertRows(session, rows);
        }
      }
      count = rows.size();
    } catch (SQLException e) {
      failure = convertException(e);
      rollbackBulkSavepoint(map, savepointId, failure);
      throw failure;
    } catch (RuntimeException e) {
      failure = e;
      rollbackBulkSavepoint(map, savepointId, failure);
      throw e;
    } finally {
      recordSqlDiagnostic("INSERT", "BULK_ADD_ROW", startMillis, failure);
    }
    analyzeIfRequired(session);
    return count;
  }

  private static void rollbackBulkSavepoint(TxnMap2 map, long savepointId,
      RuntimeException failure) {
    try {
      map.rollbackTo(savepointId);
    } catch (RuntimeException rollbackFailure) {
      failure.addSuppressed(rollbackFailure);
    }
  }

  private static boolean isExistingRow(RowValue old) {
    return old != null && !old.deleted && old.payload != null
        && old.payload.length > 0;
  }

  private void prepareBulkRow(Row row) {
    if (primaryIndex.getMainIndexColumn() == SearchRow.ROWID_INDEX) {
      if (row.getKey() == 0) {
        row.setKey(nextKey());
      }
      return;
    }
    long key = row.getValue(primaryIndex.getMainIndexColumn()).getLong();
    row.setKey(key);
  }

  private static RowValue rowValue(long txnId, Row row) {
    RowValue value = new RowValue();
    value.txnId = txnId;
    value.commitTs = 0L;
    value.deleted = false;
    value.payload = RowCodec.encode(row);
    return value;
  }

  private DbException duplicateKey(long rowId, RowValue old) {
    int errorCode = ErrorCode.DUPLICATE_KEY_1;
    Row oldRow = old != null && old.payload != null
        ? RowCodec.decode(rowId, old.payload) : null;
    DbException e = DbException.get(errorCode,
        getName() + " primary key " + rowId + ' ' + oldRow);
    e.setSource(primaryIndex);
    return e;
  }

  private static final class BulkRowWrite {
    private final RowKey rowKey;
    private final RowValue value;
    private final RowValue oldValue;

    private BulkRowWrite(RowKey rowKey, RowValue value, RowValue oldValue) {
      this.rowKey = rowKey;
      this.value = value;
      this.oldValue = oldValue;
    }
  }

  @Override
  public void updateRow(SessionLocal session, Row oldRow, Row newRow) {
    long startMillis = System.currentTimeMillis();
    RuntimeException failure = null;
    newRow.setKey(oldRow.getKey());
    syncLastModificationIdWithDatabase();
    Transaction t = session.getTransaction();
    long savepoint = t.setSavepoint();
    try {
      for (Index index : indexes) {
        index.update(session, oldRow, newRow);
      }
      if(session.getAutoCommit()){
        session.commit(false);
      }
    } catch (Throwable e) {
      try {
        t.rollbackToSavepoint(savepoint);
      } catch (Throwable nested) {
        e.addSuppressed(nested);
      }
      failure = DbException.convert(e);
      throw failure;
    } finally {
      recordSqlDiagnostic("UPDATE", "UPDATE_ROW", startMillis, failure);
    }
    analyzeIfRequired(session);
  }

  @Override
  public Row lockRow(SessionLocal session, Row row, int timeoutMillis) {
    Row lockedRow = primaryIndex.lockRow(session, row, timeoutMillis);

    if (lockedRow == null || !row.hasSharedData(lockedRow)) {
      syncLastModificationIdWithDatabase();
    }
    return lockedRow;
  }

  private void analyzeIfRequired(SessionLocal session) {
    if (changesUntilAnalyze != null) {
      if (changesUntilAnalyze.decrementAndGet() == 0) {
        if (nextAnalyze <= Integer.MAX_VALUE / 2) {
          nextAnalyze *= 2;
        }
        changesUntilAnalyze.set(nextAnalyze);
        session.markTableForAnalyze(this);
      }
    }
  }

  @Override
  public Index getScanIndex(SessionLocal session) {
    return primaryIndex;
  }

  @Override
  public ArrayList<Index> getIndexes() {
    return indexes;
  }

  @Override
  public long getMaxDataModificationId() {
    return lastModificationId.get();
  }

  @Override
  public void removeChildrenAndResources(SessionLocal session) {
    if (containsLargeObject) {
      // unfortunately, the data is gone on rollback
      truncate(session);
      database.getLobStorage().removeAllForTable(getId());
      database.lockMeta(session);
    }
    //database.getStore().removeTable(this);
    super.removeChildrenAndResources(session);
    // remove scan index (at position 0 on the list) last
    while (indexes.size() > 1) {
      Index index = indexes.get(1);
      index.remove(session);
      if (index.getName() != null) {
        database.removeSchemaObject(session, index);
      }
      // needed for session temporary indexes
      indexes.remove(index);
    }
    primaryIndex.remove(session);
    indexes.clear();
    close(session);
    invalidate();
  }


  @Override
  public long getRowCountApproximation(SessionLocal session) {
    return primaryIndex.getRowCountApproximation(session);
  }

  @Override
  public long getDiskSpaceUsed() {
    return primaryIndex.getDiskSpaceUsed();
  }

  /**
   * Get a new transaction.
   *
   * @return the transaction
   */
  Transaction getTransactionBegin() {
    // TODO need to commit/rollback the transaction
    return transactionStore.begin();
  }


  /**
   * Mark the transaction as committed, so that the modification counter of
   * the database is incremented.
   */
  public void commit() {
    if (database != null) {
      syncLastModificationIdWithDatabase();
    }
  }

  // Field lastModificationId can not be just a volatile, because window of opportunity
  // between reading database's modification id and storing this value in the field
  // could be exploited by another thread.
  // Second thread may do the same with possibly bigger (already advanced)
  // modification id, and when first thread finally updates the field, it will
  // result in lastModificationId jumping back.
  // This is, of course, unacceptable.
  private void syncLastModificationIdWithDatabase() {
    long nextModificationDataId = database.getNextModificationDataId();
    long currentId;
    do {
      currentId = lastModificationId.get();
    } while (nextModificationDataId > currentId &&
        !lastModificationId.compareAndSet(currentId, nextModificationDataId));
  }

  public DbException convertException(SQLException e) {
    return convertException(e, false);
  }

  public DbException convertLockException(SQLException e) {
    return convertException(e, true);
  }

  private DbException convertException(SQLException ex, boolean lockException) {
    return DbException.get(ErrorCode.GENERAL_ERROR_1, ex, ex.getMessage());
  }



  @Override
  public int getMainIndexColumn() {
    return primaryIndex.getMainIndexColumn();
  }


  /**
   * Appends the specified rows to the specified index.
   *
   * @param session
   *            the session
   * @param list
   *            the rows, list is cleared on completion
   * @param index
   *            the index to append to
   */
  private static void addRowsToIndex(SessionLocal session, ArrayList<Row> list, Index index) {
    sortRows(list, index);
    for (Row row : list) {
      index.add(session, row);
    }
    list.clear();
  }

  /**
   * Formats details of a deadlock.
   *
   * @param sessions
   *            the list of sessions
   * @param lockType
   *            the type of lock
   * @return formatted details of a deadlock
   */
  private static String getDeadlockDetails(ArrayList<SessionLocal> sessions, int lockType) {
    // We add the thread details here to make it easier for customers to
    // match up these error messages with their own logs.
    StringBuilder builder = new StringBuilder();
    for (SessionLocal s : sessions) {
      Table lock = s.getWaitForLock();
      Thread thread = s.getWaitForLockThread();
      builder.append("\nSession ").append(s).append(" on thread ").append(thread.getName())
          .append(" is waiting to lock ").append(lock.toString())
          .append(" (").append(lockTypeToString(lockType)).append(") while locking ");
      boolean addComma = false;
      for (Table t : s.getLocks()) {
        if (addComma) {
          builder.append(", ");
        }
        addComma = true;
        builder.append(t.toString());
        if (t instanceof AdbTable) {
          if (((AdbTable) t).lockExclusiveSession == s) {
            builder.append(" (exclusive)");
          } else {
            builder.append(" (shared)");
          }
        }
      }
      builder.append('.');
    }
    return builder.toString();
  }

  private static String lockTypeToString(int lockType) {
    return lockType == Table.READ_LOCK ? "shared read"
        : lockType == Table.WRITE_LOCK ? "shared write" : "exclusive";
  }

  /**
   * Sorts the specified list of rows for a specified index.
   *
   * @param list
   *            the list of rows
   * @param index
   *            the index to sort for
   */
  private static void sortRows(ArrayList<? extends SearchRow> list, final Index index) {
    list.sort(index::compareRows);
  }

  @Override
  public boolean canDrop() {
    return true;
  }

  @Override
  public boolean canGetRowCount(SessionLocal session) {
    return true;
  }

  @Override
  public boolean canTruncate() {
    if (getCheckForeignKeyConstraints() && database.getReferentialIntegrity()) {
      ArrayList<Constraint> constraints = getConstraints();
      if (constraints != null) {
        for (Constraint c : constraints) {
          if (c.getConstraintType() != Constraint.Type.REFERENTIAL) {
            continue;
          }
          ConstraintReferential ref = (ConstraintReferential) c;
          if (ref.getRefTable() == this) {
            return false;
          }
        }
      }
    }
    return true;
  }

  @Override
  public ArrayList<SessionLocal> checkDeadlock(SessionLocal session, SessionLocal clash, Set<SessionLocal> visited) {
    // only one deadlock check at any given time
    synchronized (getClass()) {
      if (clash == null) {
        // verification is started
        clash = session;
        visited = new HashSet<>();
      } else if (clash == session) {
        // we found a cycle where this session is involved
        return new ArrayList<>(0);
      } else if (visited.contains(session)) {
        // we have already checked this session.
        // there is a cycle, but the sessions in the cycle need to
        // find it out themselves
        return null;
      }
      visited.add(session);
      ArrayList<SessionLocal> error = null;
      for (SessionLocal s : lockSharedSessions.keySet()) {
        if (s == session) {
          // it doesn't matter if we have locked the object already
          continue;
        }
        Table t = s.getWaitForLock();
        if (t != null) {
          error = t.checkDeadlock(s, clash, visited);
          if (error != null) {
            error.add(session);
            break;
          }
        }
      }
      // take a local copy so we don't see inconsistent data, since we are
      // not locked while checking the lockExclusiveSession value
      SessionLocal copyOfLockExclusiveSession = lockExclusiveSession;
      if (error == null && copyOfLockExclusiveSession != null) {
        Table t = copyOfLockExclusiveSession.getWaitForLock();
        if (t != null) {
          error = t.checkDeadlock(copyOfLockExclusiveSession, clash, visited);
          if (error != null) {
            error.add(session);
          }
        }
      }
      return error;
    }
  }

  @Override
  public void checkSupportAlter() {
    // ok
  }

  public boolean getContainsLargeObject() {
    return containsLargeObject;
  }

  @Override
  public Column getRowIdColumn() {
    if (rowIdColumn == null) {
      rowIdColumn = new Column(Column.ROWID, TypeInfo.TYPE_BIGINT, this, SearchRow.ROWID_INDEX);
      rowIdColumn.setRowId(true);
      rowIdColumn.setNullable(false);
    }
    return rowIdColumn;
  }

  @Override
  public TableType getTableType() {
    return TableType.TABLE;
  }

  @Override
  public boolean isDeterministic() {
    return true;
  }

  @Override
  public boolean isLockedExclusively() {
    return lockExclusiveSession != null;
  }

  @Override
  public boolean isLockedExclusivelyBy(SessionLocal session) {
    return lockExclusiveSession == session;
  }

  @Override
  protected void invalidate() {
    super.invalidate();
    /*
     * Query cache of a some sleeping session can have references to
     * invalidated tables. When this table was dropped by another session,
     * the field below still points to it and prevents its garbage
     * collection, so this field needs to be cleared to prevent a memory
     * leak.
     */
    lockExclusiveSession = null;
  }

  @Override
  public String toString() {
    return getTraceSQL();
  }

  /**
   * Prepares columns of an index.
   *
   * @param database the database
   * @param cols the index columns
   * @param indexType the type of an index
   * @return the prepared columns with flags set
   */
  private static IndexColumn[] prepareColumns(Database database, IndexColumn[] cols, IndexType indexType) {
    if (indexType.isPrimaryKey()) {
      for (IndexColumn c : cols) {
        Column column = c.column;
        if (column.isNullable()) {
          throw DbException.get(ErrorCode.COLUMN_MUST_NOT_BE_NULLABLE_1, column.getName());
        }
      }
      for (IndexColumn c : cols) {
        c.column.setPrimaryKey(true);
      }
    } else if (!indexType.isSpatial()) {
      int i = 0, l = cols.length;
      while (i < l && (cols[i].sortType & (SortOrder.NULLS_FIRST | SortOrder.NULLS_LAST)) != 0) {
        i++;
      }
      if (i != l) {
        cols = cols.clone();
        DefaultNullOrdering defaultNullOrdering = database.getDefaultNullOrdering();
        for (; i < l; i++) {
          IndexColumn oldColumn = cols[i];
          int sortTypeOld = oldColumn.sortType;
          int sortTypeNew = defaultNullOrdering.addExplicitNullOrdering(sortTypeOld);
          if (sortTypeNew != sortTypeOld) {
            IndexColumn newColumn = new IndexColumn(oldColumn.columnName, sortTypeNew);
            newColumn.column = oldColumn.column;
            cols[i] = newColumn;
          }
        }
      }
    }
    return cols;
  }
}
