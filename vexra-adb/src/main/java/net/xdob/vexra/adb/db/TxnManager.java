package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.*;
import net.xdob.vexra.adb.key.*;
import net.xdob.vexra.adb.util.Utils;
import org.h2.api.ErrorCode;
import org.h2.message.DbException;
import org.h2.value.Value;
import org.h2.value.ValueBigint;
import org.h2.value.ValueInteger;
import org.h2.value.ValueNull;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletionException;

public class TxnManager {

  private TxnIdGenerator txnIdGen;
  private CommitTSGenerator tsGen;
  private DbStore store;
  private final LockManager lockManager = new LockManager();
  private final Object commitMutex = new Object();
  private final Map<Long, Transaction2> activeTransactions =
      new java.util.concurrent.ConcurrentHashMap<>();
  private volatile AdbRegionWriteGate regionWriteGate = AdbRegionWriteGate.NOOP;
  private volatile AdbRegionReadRouter regionReadRouter = AdbRegionReadRouter.NOOP;
  private volatile AdbRegionCommitCoordinator regionCommitCoordinator;
  private volatile AdbTimestampProvider timestampProvider;

  public TxnManager(DbStore store) {
    this.store = store;
    this.txnIdGen = new TxnIdGenerator(store);
    this.tsGen = new CommitTSGenerator(store);
  }

  public DbStore getStore() {
    return store;
  }

  public LockManager getLockManager() {
    return lockManager;
  }

  public AdbRegionWriteGate getRegionWriteGate() {
    return regionWriteGate;
  }

  /**
   * 设置 ADB region 写入 gate。
   *
   * <p>传入 null 会恢复为 no-op gate。gate 在 commitTs 分配和 durable commit 前执行，
   * 用于分布式 region 模式下阻止缺少多数派的写入。</p>
   *
   * @param regionWriteGate 新的 region 写入 gate
   */
  public void setRegionWriteGate(AdbRegionWriteGate regionWriteGate) {
    this.regionWriteGate = regionWriteGate == null
        ? AdbRegionWriteGate.NOOP : regionWriteGate;
  }

  public AdbRegionReadRouter getRegionReadRouter() {
    return regionReadRouter;
  }

  /**
   * 设置 ADB region 读路由器。
   *
   * <p>传入 null 会恢复为 no-op router。router 在点读和扫描创建本地 cursor 前执行，
   * 用于分布式 region 模式下记录或校验读请求的 region 路由。</p>
   *
   * @param regionReadRouter 新的 region 读路由器
   */
  public void setRegionReadRouter(AdbRegionReadRouter regionReadRouter) {
    this.regionReadRouter = regionReadRouter == null
        ? AdbRegionReadRouter.NOOP : regionReadRouter;
  }

  public AdbRegionCommitCoordinator getRegionCommitCoordinator() {
    return regionCommitCoordinator;
  }

  /**
   * 设置 ADB region commit 协调器。
   *
   * <p>传入 null 会恢复为直接调用底层 store commit。启用后，事务 durable commit 会先
   * 按 write set 路由到 region，再交由 coordinator 调用 region commit client。</p>
   *
   * @param regionCommitCoordinator 新的 region commit 协调器
   */
  public void setRegionCommitCoordinator(
      AdbRegionCommitCoordinator regionCommitCoordinator) {
    this.regionCommitCoordinator = regionCommitCoordinator;
  }

  public AdbTimestampProvider getTimestampProvider() {
    return timestampProvider;
  }

  /**
   * 设置外部 timestamp provider。
   *
   * <p>传入 null 会恢复为本地 commitTs 计数器。启用后，新事务 startTs 和 commitTs
   * 都由外部控制面 TSO 分配。</p>
   *
   * @param timestampProvider 外部 timestamp provider
   */
  public void setTimestampProvider(AdbTimestampProvider timestampProvider) {
    this.timestampProvider = timestampProvider;
  }

  public long newTxnId() {
    return txnIdGen.nextTxnId();
  }

  public long lastCommitTs() {
    AdbTimestampProvider provider = timestampProvider;
    if (provider != null) {
      return provider.lastTimestamp();
    }
    return tsGen.lastCommitTs();
  }

  public Transaction2 beginTransaction() {
    long startTs = nextStartTs();
    Transaction2 txn = new Transaction2(txnIdGen.nextTxnId(), startTs);
    txn.setStartTs(startTs);
    txn.setState(TxnState.PENDING);
    activeTransactions.put(txn.getTxnId(), txn);
    return txn;
  }

  /**
   * 返回当前活跃事务 startTs 快照。
   *
   * <p>该快照只包含本进程内已经 begin、但尚未 commit/rollback 成功的事务。
   * GC safe point 推进器使用它保护长事务，避免删除仍可能被快照读访问的历史版本。</p>
   *
   * @return 活跃事务 startTs 的只读快照
   */
  public List<Long> activeStartTsSnapshot() {
    List<Long> startTs = new ArrayList<>();
    for (Transaction2 txn : activeTransactions.values()) {
      if (TxnState.PENDING.equals(txn.getState())
          || TxnState.COMMITTING.equals(txn.getState())) {
        startTs.add(txn.getStartTs());
      }
    }
    Collections.sort(startTs);
    return Collections.unmodifiableList(startTs);
  }

  private long nextStartTs() {
    AdbTimestampProvider provider = timestampProvider;
    if (provider != null) {
      return provider.nextStartTimestamp();
    }
    return tsGen.lastCommitTs();
  }

  // -------------------- 鍐?鍒犻櫎鎿嶄綔 --------------------
  public void put(Transaction2 txn, DataKey key, RowValue value) throws SQLException {
    store.writeBatch(s -> {
      txn.put(s, key, value);
    });
  }

  public IndexBuildState getIndexBuildState(TabId tId, int indexId) throws SQLException {

    IndexStatusKey indexMetaKey = IndexStatusKey.of(tId, indexId);
    byte[] bytes = store.get(CF.META.getCfId(), indexMetaKey.toBytes());
    if(bytes==null){
      return IndexBuildState.BUILDING;
    }
    Value value = RowCodec.decode(bytes);
    return IndexBuildState.getByCode((byte) value.getInt());
  }

  public void setIndexBuildState(TabId tId, int indexId, IndexBuildState state) throws SQLException {
    IndexStatusKey indexMetaKey = IndexStatusKey.of(tId, indexId);
    store.writeBatch(s->{
      Value value = ValueInteger.get(state.getCode());
      s.put(CF.META.getCfId(), indexMetaKey.toBytes(), RowCodec.encode( value));
    });
  }

  public long getRowCount(Transaction2 txn, TabId tId) throws SQLException {
    long baseRowCount = getBaseRowCount(RowCountKey.of(tId));
    long rowCountDelta = txn.getRowCountDelta(RowCountDeltaKey.of(tId));
    return baseRowCount + rowCountDelta;
  }

  public RowValue getVisibleBaseRowCount(RowCountKey key) throws SQLException {
    byte[] prefix = key.toBytes();
    byte[] end = KeyCodec.prefixEnd(prefix);

    try (VersionScanSource scan = store.openVersionScanSource(CF.META.getCfId(), ScanDirection.FORWARD)) {
      scan.seekToRangeStart(prefix, end);

      while (scan.isValid() && KeyCodec.startsWith(scan.key(), prefix)) {
        VersionRowCountKey versionKey = VersionRowCountKey.fromBytes(scan.key());
        RowValue val = RowValue.decodeValue(scan.value());

        if (versionKey.getCommitTs() > 0) {
          if (val.deleted) {
            return null;
          }
          val.commitTs = versionKey.getCommitTs();  // 鍏抽敭琛ヤ笂
          return val;
        }

        scan.advance();
      }

      return null;
    } catch (Exception e) {
      if (e instanceof SQLException) {
        throw (SQLException) e;
      }
      throw new SQLException("Failed to resolve visible base row count", e);
    }
  }

  public long getBaseRowCount(RowCountKey key) throws SQLException {
    long rowCount = 0;
    RowValue visible = getVisibleBaseRowCount(key);
    long baseCommitTs = 0;

    if (visible != null && !visible.deleted && visible.payload != null) {
      rowCount = RowCodec.decode(visible.payload).getLong();
      baseCommitTs = visible.commitTs;
    }

    RowCountDeltaKey rowCountDeltaKey = RowCountDeltaKey.of(key.getTabKey());
    VersionRowCountDeltaKey startDeltaKey =
        VersionRowCountDeltaKey.of(key.getTabKey(), baseCommitTs);

    byte[] prefix = rowCountDeltaKey.toBytes();
    byte[] start = startDeltaKey.toBytes();
    byte[] end = KeyCodec.prefixEnd(prefix);

    try (VersionScanSource scan = store.openVersionScanSource(CF.META.getCfId(), ScanDirection.FORWARD)) {
      scan.seekToRangeStart(start, end);

      while (scan.isValid() && KeyCodec.startsWith(scan.key(), prefix)) {
        VersionRowCountDeltaKey deltaKey = VersionRowCountDeltaKey.fromBytes(scan.key());
        if (deltaKey.getCommitTs() > baseCommitTs) {
          RowValue val = RowValue.decodeValue(scan.value());
          if (!val.deleted && val.payload != null) {
            rowCount += RowCodec.decode(val.payload).getLong();
          }
        }
        scan.advance();
      }
      return rowCount;
    } catch (Exception e) {
      if (e instanceof SQLException) throw (SQLException) e;
      throw new SQLException("Failed to resolve base row count", e);
    }
  }


  public RowValue delete(Transaction2 txn, DataKey key) throws SQLException {
    RowValue result = getVisible(txn, key);
    if(result!=null) {
      store.writeBatch(s -> {
        txn.delete(s, key);
      });
      return result;
    }
    return null;
  }

  public void addIndexBatch(Transaction2 txn, IndexPrefix indexPrefix, Collection<IndexKey> indexKeys) throws SQLException {
    store.writeBatch(batch -> {
      //todo 绉婚櫎鑰佺殑绱㈠紩
//      byte[] indexPrefixBytes = indexPrefix.toBytes();
//      byte[] prefixEnd = KeyCodec.prefixEnd(indexPrefixBytes);
//      batch.deleteRange(indexPrefixBytes, prefixEnd);
      for (IndexKey indexKey : indexKeys) {
        RowValue indexValue = new RowValue();
        indexValue.payload = RowCodec.encode(ValueNull.INSTANCE);
        VersionKey versionKey = VersionKey.of(indexKey, true, txn.getTxnId());
        indexValue.commitTs = txn.getTxnId();
        batch.put(versionKey.toBytes(), RowValue.encodeValue(indexValue));
      }
    });
  }

  public IndexScanCursor indexScanIterator(Transaction2 txn, PrefixKey prefixKey, TableKey min, TableKey max){
    routeRangeRead(txn,
        min != null ? min.toBytes() : prefixKey.toBytes(),
        max != null ? max.toBytes() : KeyCodec.prefixEnd(prefixKey.toBytes()));
    return new IndexScanCursor(txn, store.openVersionScanSource(ScanDirection.FORWARD),
        new DefaultVisibleIndexResolver(store), new DefaultVisibleRowResolver(store),
        prefixKey, min, max);
  }

  public TableScanCursor entryIterator(Transaction2 txn, PrefixKey prefixKey, Long min, Long max){
    routeRangeRead(txn,
        tableScanStartKey(prefixKey, min), tableScanEndKey(prefixKey, max));
    return new TableScanCursor(txn, store.openVersionScanSource(ScanDirection.FORWARD),
        new DefaultVisibleRowResolver(store),
        prefixKey, min, max);
    //return new TableScanCursor(store, txn, prefixKey , min, max, false);
  }


  public RowValue getVisibleCommitted(Transaction2 txn, DataKey key) throws SQLException {
    VersionResolver resolver = new DefaultVersionResolver(store);
    return resolver.getLatestCommittedBefore(key, txn.getStartTs());
  }

  public RowValue getLatestCommitted(Key key) throws SQLException {
    VersionResolver resolver = new DefaultVersionResolver(store);
    return resolver.getLatestCommitted(key);
  }

  public RowValue getVisible(Transaction2 txn, DataKey rowKey) throws SQLException {
    // 1. 鍏堢湅褰撳墠浜嬪姟鏈湴 writeSet
    RowValue local = txn.getLocalWrite(rowKey);
    if (local != null) {
      return local;
    }

    regionReadRouter.routePointRead(txn, rowKey);

    // 2. 璇?committed 鎴?Intent
    RowValue visible = this.getVisibleCommitted(txn, rowKey);

    long version = visible == null ? 0L : visible.commitTs;
    txn.recordRead(rowKey, version);
    return visible;
  }



  // -------------------- 璇绘搷浣?--------------------
  public RowValue first(Transaction2 txn, PrefixKey prefixKey) throws SQLException {
    VersionResolver resolver = new DefaultVersionResolver(store);
    return resolver.first(txn, prefixKey);
  }

  public RowValue last(Transaction2 txn, PrefixKey prefixKey) throws SQLException {
    VersionResolver resolver = new DefaultVersionResolver(store);
    return resolver.last(txn, prefixKey);
  }

//  public RowValue get(Transaction2 txn, DataKey key) throws SQLException {
//    return getVisible(txn, key);
//  }



  private void validate(Transaction2 txn) throws SQLException {

    for (Map.Entry<DataKey, RowValue> e : txn.getWriteSet().entrySet()) {
      DataKey key = e.getKey();

      Long readVersion = txn.getReadVersion(key);
      if (readVersion == null) {
        //throw new IllegalStateException("missing read version for key: " + key);
        continue;
      }
      RowValue latest = getLatestCommitted(key);
      long currentVersion = latest == null ? 0L : latest.commitTs;


      // 濡傛灉鐗堟湰娌″彉 鈫?OK
      if (currentVersion == readVersion) {
        continue;
      }

      // 濡傛灉鍙樹簡锛岃鍒ゆ柇鏄笉鏄€滆嚜宸卞啓鐨勨€?
      if (txn.hasWritten(key)) {
        continue;
      }

      // 鍚﹀垯鎵嶆槸鍐茬獊
      throw DbException.get(ErrorCode.CONCURRENT_UPDATE_1, key.toString());
    }
  }


  // -------------------- 鎻愪氦浜嬪姟 --------------------
  public void commit(Transaction2 txn) throws SQLException {
    final long commitTs;
    final ArrayList<DataKey> writeKeys;
    final LinkedHashMap<RowCountDeltaKey, Long> rowCountDeltas;
    final LinkedHashMap<Integer, Long> tableEpochUpdates = new LinkedHashMap<>();

    synchronized (commitMutex) {
      if (TxnState.COMMITTED.equals(txn.getState())) {
        activeTransactions.remove(txn.getTxnId());
        return;
      }
      if (TxnState.COMMITTING.equals(txn.getState())) {
        throw new SQLException("Transaction is already committing: " + txn.getTxnId());
      }
      if (TxnState.ABORTED.equals(txn.getState())) {
        throw new SQLException("Transaction has been aborted: " + txn.getTxnId());
      }

      validate(txn);
      writeKeys = new ArrayList<>(txn.getWriteSet().keySet());
      regionWriteGate.beforeCommit(txn, writeKeys);
      commitTs = nextCommitTs();
      txn.setState(TxnState.COMMITTING);

      rowCountDeltas = new LinkedHashMap<>();
      for (RowCountDeltaKey key : txn.getRowCountDeltaKeySet()) {
        rowCountDeltas.put(key, txn.getRowCountDelta(key));
      }

      for (Map.Entry<Integer, Epoch> entry : txn.getTableEpochs().entrySet()) {
        Integer tableId = entry.getKey();
        Epoch epoch = entry.getValue();
        if (tableId != null && epoch != null && epoch.intent) {
          tableEpochUpdates.put(tableId, epoch.epoch);
        }
      }
    }

    try {
      List<Meta> metas = new ArrayList<>();
      for (Map.Entry<RowCountDeltaKey, Long> entry : rowCountDeltas.entrySet()) {
        RowCountDeltaKey key = entry.getKey();
        long rowCountDelta = entry.getValue();
        if (rowCountDelta != 0){
          VersionRowCountDeltaKey tableStatsKey =
              VersionRowCountDeltaKey.of(key.getTabKey(), commitTs);

          RowValue tableStats = new RowValue();
          tableStats.deleted = false;
          tableStats.payload = RowCodec.encode(ValueBigint.get(rowCountDelta));
          tableStats.commitTs = commitTs;
          metas.add(Meta.of(tableStatsKey.toBytes(), RowValue.encodeValue(tableStats)));
          //System.out.println("tabId="+key.getTabKey()+"  rowCountDelta = " + rowCountDelta);
        }
      }

      for (Map.Entry<Integer, Long> entry : tableEpochUpdates.entrySet()) {
        TableEpochKey tableEpochKey = TableEpochKey.of(entry.getKey());
        metas.add(Meta.of(tableEpochKey.toBytes(), Utils.encodeLong(entry.getValue())));
      }
      commitAsync(txn, commitTs, writeKeys, metas).join();

      txn.afterCommitSuccess(commitTs);
      activeTransactions.remove(txn.getTxnId());
    } catch (CompletionException e) {
      Throwable cause = unwrapCompletionException(e);

      // commit 澶辫触鍚庢仮澶嶇姸鎬侊紝閬垮厤浜嬪姟鍗℃鍦?COMMITTING
      txn.setState(TxnState.PENDING);

      if (cause instanceof SQLException) {
        throw (SQLException) cause;
      }
      throw new SQLException("Commit failed for txnId=" + txn.getTxnId(), cause);
    } catch (RuntimeException e) {
      txn.setState(TxnState.PENDING);
      throw e;
    }
  }


  private static Throwable unwrapCompletionException(Throwable t) {
    while (t instanceof CompletionException && t.getCause() != null) {
      t = t.getCause();
    }
    return t;
  }

  private void routeRangeRead(Transaction2 txn, byte[] startKeyInclusive,
      byte[] endKeyExclusive) {
    try {
      regionReadRouter.routeRangeRead(txn, startKeyInclusive, endKeyExclusive);
    } catch (SQLException e) {
      throw DbException.convert(e);
    }
  }

  private java.util.concurrent.CompletableFuture<Void> commitAsync(
      Transaction2 txn, long commitTs, Collection<DataKey> writeKeys,
      List<Meta> metas) throws SQLException {
    AdbRegionCommitCoordinator coordinator = regionCommitCoordinator;
    if (coordinator != null && !writeKeys.isEmpty()) {
      return coordinator.commitAsync(txn, commitTs, writeKeys, metas);
    }
    return store.commitAsync(txn.getTxnId(), commitTs, metas);
  }

  private long nextCommitTs() {
    AdbTimestampProvider provider = timestampProvider;
    if (provider != null) {
      return provider.nextCommitTimestamp();
    }
    return tsGen.nextCommitTs();
  }

  private static byte[] tableScanStartKey(PrefixKey prefixKey, Long minRowId) {
    return minRowId != null ? buildRowSeekKey(prefixKey, minRowId)
        : prefixKey.toBytes();
  }

  private static byte[] tableScanEndKey(PrefixKey prefixKey, Long maxRowId) {
    return maxRowId != null ? KeyCodec.prefixEnd(
        buildRowSeekKey(prefixKey, maxRowId))
        : KeyCodec.prefixEnd(prefixKey.toBytes());
  }

  private static byte[] buildRowSeekKey(PrefixKey prefixKey, long rowId) {
    DynamicByteBuffer b = DynamicByteBuffer.c();
    b.put(prefixKey.toBytes());
    b.putLong(rowId);
    return b.toArray();
  }


  // -------------------- 鍥炴粴浜嬪姟 --------------------
  public void rollback(Transaction2 txn, long savepointId) throws SQLException {
    Savepoint2 sp = txn.getSavepoint(String.valueOf(savepointId));
    if(sp!=null){
      store.writeBatch(batch->txn.rollback(batch, sp));
    }
  }

  public void rollback(Transaction2 txn) throws SQLException {
    store.rollbackAsync( txn.getTxnId()).join();
    txn.afterRollbackSuccess();
    activeTransactions.remove(txn.getTxnId());
  }



}
