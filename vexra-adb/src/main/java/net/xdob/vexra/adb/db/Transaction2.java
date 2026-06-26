package net.xdob.vexra.adb.db;


import net.xdob.vexra.adb.key.*;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;

public class Transaction2 {
  private long txnId;
  private volatile long startTs;
  private final Map<RowCountDeltaKey,AtomicLong> rowCountDeltas = new ConcurrentHashMap<>();
  private final Map<SegmentRowCountDeltaKey, AtomicLong>
      segmentRowCountDeltas = new ConcurrentHashMap<>();

  private TxnState state = TxnState.PENDING;
  private final List<OLdEntry> undoLogs = new ArrayList<>(128);
  private final Map<String, Savepoint2> savepoints = new HashMap<>();

  // key -> 璇诲埌鐨?committed version(commitTs), 涓嶅瓨鍦ㄨ 0
  private final Map<DataKey, Long> readVersions = new HashMap<>();

  // key -> 褰撳墠浜嬪姟鏈€缁堝噯澶囨彁浜ょ殑鍊?
  private final Map<DataKey, RowValue> writeSet = new LinkedHashMap<>(128);

  private final Map<TabId, LocalRowWriteBounds> localRowWriteBounds =
      new HashMap<>();

  private final ConcurrentHashMap<Integer, Epoch> tableEpochs = new ConcurrentHashMap<>();

  public Transaction2(long txnId, long startTs) {
    this.txnId = txnId;
    this.startTs = startTs;
  }

  public long getTxnId() {
    return txnId;
  }

  public void setTxnId(long txnId) {
    this.txnId = txnId;
  }

  public long getStartTs() {
    return startTs;
  }

  public void setStartTs(long startTs) {
    this.startTs = startTs;
  }


  public void recordRead(DataKey key, long version) {
    readVersions.putIfAbsent(key, version);
  }

  public Long getReadVersion(DataKey key) {
    return readVersions.get(key);
  }

  Map<DataKey, Long> getReadVersions() {
    return readVersions;
  }

  public void recordWrite(DataKey key, RowValue value) {
    writeSet.put(key, value);
  }

  public RowValue getLocalWrite(DataKey key) {
    return writeSet.get(key);
  }

  public Map<DataKey, RowValue> getWriteSet() {
    return writeSet;
  }

  public boolean hasWritten(DataKey key) {
    return writeSet.containsKey(key);
  }

  public Map<Integer, Epoch> getTableEpochs() {
    return Collections.unmodifiableMap(tableEpochs);
  }

  void clearLocalState() {
    readVersions.clear();
    writeSet.clear();
    localRowWriteBounds.clear();
    undoLogs.clear();
    savepoints.clear();
    rowCountDeltas.clear();
    segmentRowCountDeltas.clear();
    tableEpochs.clear();
  }

  public TxnState getState() {
    return state;
  }

  public void setState(TxnState state) {
    this.state = state;
  }

  public List<OLdEntry> getUndoLogs() {
    return undoLogs;
  }

  public long getRowCountDelta(RowCountDeltaKey key) {
    return getRowCountDelta2(key).get();
  }

  private AtomicLong getRowCountDelta2(RowCountDeltaKey key) {
    return rowCountDeltas.computeIfAbsent(key, k -> new AtomicLong(0));
  }

  private AtomicLong getSegmentRowCountDelta(
      SegmentRowCountDeltaKey key) {
    return segmentRowCountDeltas.computeIfAbsent(key,
        k -> new AtomicLong(0));
  }

  private void addRowCountDelta(DataKey key, long delta) {
    if (key == null || !key.isRow() || delta == 0L) {
      return;
    }
    getRowCountDelta2(RowCountDeltaKey.of(key.getTabID()))
        .addAndGet(delta);
    if (TxnManager.rangeCountSegmentsEnabled()) {
      long segmentId = TxnManager.segmentIdForRowId(key.getRowId());
      getSegmentRowCountDelta(SegmentRowCountDeltaKey.of(key.getTabID(),
          segmentId)).addAndGet(delta);
    }
  }

  public void put(AdbWriteBatch batch, DataKey key, RowValue value) throws SQLException {
    RowValue oldValue = getVisible(batch, key);
    put(batch, key, value, oldValue);
  }

  /**
   * 写入当前事务 intent，并复用调用方已经读取过的旧可见版本。
   *
   * <p>SQL table/index 层在唯一性判断、更新判断或删除判断时通常已经读取过旧值；
   * 继续在这里重复扫描 store 会让每行写入多一次版本查找。调用方必须保证 oldValue 来自同一
   * 事务快照。</p>
   */
  public void put(AdbWriteBatch batch, DataKey key, RowValue value,
      RowValue oldValue) throws SQLException {
    putLocal(key, value, oldValue);
    writeIntent(batch, key, value);
  }

  /**
   * 只在内存事务写集中记录写入，不立即持久化 intent。
   *
   * <p>本地单机提交可以在 commit 阶段通过一个底层 write batch 直接写 committed
   * version。这样仍然保持 commit 原子性，同时避免每行 INSERT 都提前写 intent 和 txn ref。</p>
   */
  public void putLocal(DataKey key, RowValue value, RowValue oldValue) {
    value.txnId = txnId;
    value.deleted = false;
    this.recordWrite(key, value);
    recordLocalRowWriteBound(key);
    OLdEntry entry = new OLdEntry();
    entry.key = key;
    entry.oldValue = oldValue;
    undoLogs.add(entry);
    if(key.isRow()&&(oldValue==null||oldValue.deleted)){
      addRowCountDelta(key, 1L);
    }
  }

  private void writeIntent(AdbWriteBatch batch, DataKey key, RowValue value) {
    VersionKey intentKey = VersionKey.of(key, false, txnId);
    batch.put(intentKey.toBytes(), RowValue.encodeValue(value));
    TxnRefKey txnRefKey = TxnRefKey.of(txnId, TxnKeyType.WRITE_REF, CF.DEFAULT.getCfId(), intentKey);
    batch.put(CF.TXN.getCfId(), txnRefKey.toBytes(), new byte[0]);
  }

  private RowValue getVisible(AdbWriteBatch batch, DataKey dataKey) throws SQLException {
    VisibleRowResolver resolver = new DefaultVisibleRowResolver(batch.getStore());
    RowValue oldValue = resolver.getVisible(this, dataKey);
    return oldValue!=null&&!oldValue.deleted?oldValue:null;
  }


  public void delete(AdbWriteBatch batch, DataKey key) throws SQLException {
    RowValue oldValue = getVisible(batch, key);
    delete(batch, key, oldValue);
  }

  /**
   * 写入删除 intent，并复用调用方已经读取过的旧可见版本。
   *
   * @param oldValue 同一事务快照下的旧可见版本；不存在时为 null
   */
  public void delete(AdbWriteBatch batch, DataKey key, RowValue oldValue)
      throws SQLException {
    RowValue value = deleteLocal(key, oldValue);
    writeIntent(batch, key, value);
  }

  /**
   * 只在内存事务写集中记录删除，不立即持久化 intent。
   *
   * @param key 删除目标 key
   * @param oldValue 同一事务快照下的旧可见版本；不存在时为 null
   * @return 删除 intent value
   */
  public RowValue deleteLocal(DataKey key, RowValue oldValue) {
    RowValue value = new RowValue();
    value.txnId = this.getTxnId();
    value.deleted = true;
    value.payload = null;
    this.recordWrite(key, value);
    recordLocalRowWriteBound(key);
    OLdEntry entry = new OLdEntry();
    entry.key = key;
    entry.oldValue = oldValue;
    undoLogs.add(entry);
    if(key.isRow()&&(oldValue!=null&&!oldValue.deleted)){
      addRowCountDelta(key, -1L);
    }
    return value;
  }

  public void setSavepoint(String name) {
    Savepoint2 sp = new Savepoint2(undoLogs.size());
    savepoints.put(name, sp);
  }

  public Savepoint2 getSavepoint(String name) {
    return savepoints.get(name);
  }

  public void  removeIfSavepoint(Predicate<Map.Entry<String, Savepoint2>> predicate) {
    savepoints.entrySet().removeIf(predicate);
  }


  public Set<RowCountDeltaKey> getRowCountDeltaKeySet() {
    return rowCountDeltas.keySet();
  }

  boolean hasRowCountDeltas() {
    return !rowCountDeltas.isEmpty();
  }

  Map<RowCountDeltaKey, AtomicLong> rowCountDeltasForCommit() {
    return rowCountDeltas;
  }

  boolean hasSegmentRowCountDeltas() {
    return !segmentRowCountDeltas.isEmpty();
  }

  Map<SegmentRowCountDeltaKey, AtomicLong> segmentRowCountDeltasForCommit() {
    return segmentRowCountDeltas;
  }

  boolean hasTableEpochs() {
    return !tableEpochs.isEmpty();
  }

  Map<Integer, Epoch> tableEpochsForCommit() {
    return tableEpochs;
  }


  public void afterCommitSuccess(long commitTs) {
    clearLocalState();
    this.setState(TxnState.COMMITTED);
    this.startTs = commitTs;
  }


  public void afterRollbackSuccess() {
    clearLocalState();
    this.setState(TxnState.ABORTED);
  }


  public long getEpoch(int tableId, Function<Integer, Long> epochFunction){
    Epoch epoch = tableEpochs.computeIfAbsent(tableId, k -> Epoch.of(epochFunction.apply(tableId)));
    return epoch.epoch;
  }

  public void truncate(int tableId, Function<Integer, Long> epochFunction){
    Epoch epoch = tableEpochs.computeIfAbsent(tableId, k -> Epoch.of(epochFunction.apply(tableId)));
    synchronized ( epoch){
      if(!epoch.intent) {
        epoch.intent = true;
        epoch.epoch = epoch.epoch + 1;
      }
    }
  }

  public void rollback(AdbWriteBatch batch, Savepoint2 sp) throws SQLException {
    if(sp!=null){
      this.removeIfSavepoint(
          e -> e.getValue().getPoint() >= sp.getPoint());
      while (undoLogs.size() > sp.getPoint()) {
        OLdEntry old = undoLogs.remove(undoLogs.size() - 1);
        if(old!=null){
          RowValue currentVisible = getVisible(batch, old.key);
          if(old.oldValue==null||old.oldValue.deleted){
            RowValue value = new RowValue();
            value.txnId = this.getTxnId();
            value.deleted = true;
            value.payload = null;
            VersionKey intentKey = VersionKey.of(old.key, false, txnId);
            TxnRefKey txnRefKey = TxnRefKey.of(txnId, TxnKeyType.WRITE_REF, CF.DEFAULT.getCfId(), intentKey);
            //batch.put(intentKey.toBytes(), WriteContext.encodeValue(value));
            //batch.putTxn(txnRefKey.toBytes(), new byte[0]);
            batch.delete(intentKey.toBytes());
            batch.delete(CF.TXN.getCfId(), txnRefKey.toBytes());
            if(old.key.isRow()&&currentVisible !=null&&!currentVisible .deleted){
              addRowCountDelta(old.key, -1L);
            }
          }else{
            VersionKey intentKey = VersionKey.of(old.key, false, txnId);
            TxnRefKey txnRefKey = TxnRefKey.of(txnId, TxnKeyType.WRITE_REF, CF.DEFAULT.getCfId(), intentKey);
            batch.put(intentKey.toBytes(), RowValue.encodeValue(old.oldValue));
            batch.put(CF.TXN.getCfId(), txnRefKey.toBytes(), new byte[0]);
            if(old.key.isRow()&&(currentVisible ==null||currentVisible .deleted)){
              addRowCountDelta(old.key, 1L);
            }
          }
        }
      }
    }
  }

  /**
   * 判断当前事务在指定表和 rowId 范围内是否可能存在本地 row 写入。
   *
   * <p>该信息只作为 range count 的保守优化 hint。savepoint 回滚不会收缩边界，
   * 因此返回 {@code true} 只表示可能有交集；返回 {@code false} 才表示可以确定跳过
   * 本地 write-set 路径。</p>
   *
   * @param tabId 表标识
   * @param minRowId 查询下界，{@code null} 表示无下界
   * @param maxRowId 查询上界，{@code null} 表示无上界
   * @return 是否可能存在落在范围内的本地 row 写入
   */
  boolean mayHaveLocalRowWriteInRange(TabId tabId, Long minRowId,
      Long maxRowId) {
    if (writeSet.isEmpty()) {
      return false;
    }
    LocalRowWriteBounds bounds = localRowWriteBounds.get(tabId);
    return bounds != null && bounds.intersects(minRowId, maxRowId);
  }

  private void recordLocalRowWriteBound(DataKey key) {
    if (key == null || !key.isRow()) {
      return;
    }
    recordLocalRowWriteBound(key.getTabID(), key.getRowId(), key.getRowId());
  }

  private void recordLocalRowWriteBound(TabId tabId, long minRowId,
      long maxRowId) {
    if (tabId == null || minRowId == Long.MAX_VALUE
        || maxRowId == Long.MIN_VALUE) {
      return;
    }
    LocalRowWriteBounds bounds = localRowWriteBounds.computeIfAbsent(
        tabId, ignored -> new LocalRowWriteBounds());
    bounds.record(minRowId, maxRowId);
  }

  private static final class LocalRowWriteBounds {

    private long min = Long.MAX_VALUE;
    private long max = Long.MIN_VALUE;

    private void record(long minRowId, long maxRowId) {
      if (minRowId < min) {
        min = minRowId;
      }
      if (maxRowId > max) {
        max = maxRowId;
      }
    }

    private boolean intersects(Long minRowId, Long maxRowId) {
      if (min == Long.MAX_VALUE) {
        return false;
      }
      if (maxRowId != null && maxRowId.longValue() < min) {
        return false;
      }
      return minRowId == null || minRowId.longValue() <= max;
    }
  }

}
