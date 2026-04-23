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

  private TxnState state = TxnState.PENDING;
  private final List<OLdEntry> undoLogs = new ArrayList<>();
  private final Map<String, Savepoint2> savepoints = new HashMap<>();

  // key -> 读到的 committed version(commitTs), 不存在记 0
  private final Map<DataKey, Long> readVersions = new HashMap<>();

  // key -> 当前事务最终准备提交的值
  private final Map<DataKey, RowValue> writeSet = new LinkedHashMap<>();

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
    undoLogs.clear();
    savepoints.clear();
    rowCountDeltas.clear();
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

  public void put(AdbWriteBatch batch, DataKey key, RowValue value) throws SQLException {
    RowValue oldValue = getVisible(batch, key);
    value.txnId = txnId;
    value.deleted = false;

    VersionKey intentKey = VersionKey.of(key, false, txnId);
    batch.put(intentKey.toBytes(), RowValue.encodeValue(value));
    this.recordWrite(key, value);
    TxnRefKey txnRefKey = TxnRefKey.of(txnId, TxnKeyType.WRITE_REF, CF.DEFAULT.getCfId(), intentKey);
    batch.put(CF.TXN.getCfId(), txnRefKey.toBytes(), new byte[0]);
    OLdEntry entry = new OLdEntry();
    entry.key = key;
    entry.oldValue = oldValue;
    undoLogs.add(entry);
    if(key.isRow()&&(oldValue==null||oldValue.deleted)){
      getRowCountDelta2(RowCountDeltaKey.of(key.getTabID())).incrementAndGet();
    }
  }

  private RowValue getVisible(AdbWriteBatch batch, DataKey dataKey) throws SQLException {
    VisibleRowResolver resolver = new DefaultVisibleRowResolver(batch.getStore());
    RowValue oldValue = resolver.getVisible(this, dataKey);
    return oldValue!=null&&!oldValue.deleted?oldValue:null;
  }


  public void delete(AdbWriteBatch batch, DataKey key) throws SQLException {
    RowValue oldValue = getVisible(batch, key);
    RowValue value = new RowValue();
    value.txnId = this.getTxnId();
    value.deleted = true;
    value.payload = null;
    VersionKey intentKey = VersionKey.of(key, false, txnId);
    TxnRefKey txnRefKey = TxnRefKey.of(txnId, TxnKeyType.WRITE_REF, CF.DEFAULT.getCfId(), intentKey);
    batch.put(intentKey.toBytes(), RowValue.encodeValue(value));
    this.recordWrite(key, value);
    batch.put(CF.TXN.getCfId(), txnRefKey.toBytes(), new byte[0]);
    OLdEntry entry = new OLdEntry();
    entry.key = key;
    entry.oldValue = oldValue;
    undoLogs.add(entry);
    if(key.isRow()&&(oldValue!=null&&!oldValue.deleted)){
      getRowCountDelta2(RowCountDeltaKey.of(key.getTabID())).decrementAndGet();
    }
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
              getRowCountDelta2(RowCountDeltaKey.of(old.key.getTabID())).decrementAndGet();
            }
          }else{
            VersionKey intentKey = VersionKey.of(old.key, false, txnId);
            TxnRefKey txnRefKey = TxnRefKey.of(txnId, TxnKeyType.WRITE_REF, CF.DEFAULT.getCfId(), intentKey);
            batch.put(intentKey.toBytes(), RowValue.encodeValue(old.oldValue));
            batch.put(CF.TXN.getCfId(), txnRefKey.toBytes(), new byte[0]);
            if(old.key.isRow()&&(currentVisible ==null||currentVisible .deleted)){
              getRowCountDelta2(RowCountDeltaKey.of(old.key.getTabID())).incrementAndGet();
            }
          }
        }
      }
    }
  }

}
