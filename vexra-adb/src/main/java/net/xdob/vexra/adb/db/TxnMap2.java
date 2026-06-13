package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.*;
import org.h2.result.Row;
import org.h2.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class TxnMap2 {
  static final Logger LOG = LoggerFactory.getLogger(TxnMap2.class);
  private  final TxnManager txnManager;
  private final Transaction2 transaction;
  private final List<AutoCloseable> resources = new ArrayList<>();


  public  TxnMap2(TxnManager txnManager, Transaction2 transaction) {
    this.txnManager = txnManager;
    this.transaction = transaction;
  }

  /**
   * 返回当前 H2 session 绑定的 ADB 事务。
   *
   * @return 当前事务对象
   */
  public Transaction2 getTransaction() {
    return transaction;
  }

  private void put(DataKey key, RowValue value) throws SQLException {
    txnManager.put(transaction, key, value);
  }

  public RowValue put(DataKey rowKey, Value row) throws SQLException {

    RowValue oldRowValue = getVisible(rowKey);
    RowValue rowValue = oldRowValue!=null?oldRowValue:new RowValue();
    rowValue.txnId = transaction.getTxnId();
    rowValue.commitTs = 0;
    rowValue.deleted = false;
    rowValue.payload = RowCodec.encode(row);
    this.put(rowKey, rowValue);
    return oldRowValue;
  }

  public RowValue putIfAbsent(DataKey dataKey, Value row) throws SQLException {

    RowValue old = getVisible(dataKey);
    if (old == null||old.deleted||old.payload== null) {
      RowValue value = new RowValue();
      value.txnId = transaction.getTxnId();
      value.commitTs = 0;
      value.deleted = false;
      value.payload = RowCodec.encode(row);
      this.put(dataKey, value);
      return null;
    }
    return old;
  }

  public void markStatementStart(){
    transaction.setStartTs(txnManager.lastCommitTs());
  }

  public RowValue getVisible(DataKey rowKey) throws SQLException {
    return txnManager.getVisible(transaction, rowKey);
  }

  public RowValue delete(DataKey key) throws SQLException {
    return txnManager.delete(transaction, key);
  }


  public long getRowCount(int tableId) throws SQLException {
    return txnManager.getRowCount(transaction, getTabId(tableId));
  }

  public TableScanCursor entryIterator(PrefixKey prefixKey, Long min, Long max){
    return txnManager.entryIterator(transaction, prefixKey, min, max);
  }

  public IndexScanCursor indexScanIterator(PrefixKey prefixKey, TableKey min, TableKey max){
    return txnManager.indexScanIterator(transaction, prefixKey, min, max);
  }

//  public RowValue get(DataKey key) throws SQLException {
//    return txnManager.get(transaction, key);
//  }

  public RowValue first(PrefixKey tablePrefix) throws SQLException {
    return txnManager.first(transaction, tablePrefix);
  }

  public RowValue last(PrefixKey tablePrefix) throws SQLException {
    return txnManager.last(transaction, tablePrefix);
  }

  public UniqueCheckResult checkUniqueConflict(IndexKey indexKey, long newRowId) throws SQLException {
    long existingRowId = indexKey.getRowId();

    // 鍚屼竴琛岋紝鐩存帴璺宠繃
    if (existingRowId == newRowId) {
      return UniqueCheckResult.IGNORE;
    }

    DataKey rowKey = RowKey.of(getTabId(indexKey.getTableId()), existingRowId);

    // 鍙熀浜庡綋鍓嶄簨鍔″彲瑙佽鍥惧垽鏂?
    RowValue visible = getVisible(rowKey);
    if (isUsableRow(visible)) {
      return UniqueCheckResult.DUPLICATE;
    }

    return UniqueCheckResult.IGNORE;
  }

  private static boolean isUsableRow(RowValue rowValue) {
    return rowValue != null
        && !rowValue.deleted
        && rowValue.payload != null
        && rowValue.payload.length > 0;
  }

  public void commit() {
    boolean success = false;
    try {
      txnManager.commit(transaction);
      success = true;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    } finally {
      if (!success) {
        LOG.warn("txn commit failed, txnId={}", transaction.getTxnId());
      }
      txnManager.getLockManager().unlockAll(transaction.getTxnId());
    }
  }

  public void setSavepoint(long savepointId){
    transaction.setSavepoint(String.valueOf(savepointId));
  }

  public void rollbackTo(long savepointId)  {
    try {
      txnManager.rollback(transaction, savepointId);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  public void rollback()  {
    try {
      txnManager.rollback(transaction);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    } finally {
      txnManager.getLockManager().unlockAll(transaction.getTxnId());
    }
  }

  public void addIndexBatch(IndexPrefix indexPrefix, Collection<IndexKey> indexKeys) throws SQLException {
    txnManager.addIndexBatch(transaction, indexPrefix, indexKeys);
  }

  public Row lock(int tableId, long key, int timeoutMillis) throws SQLException {
    RowLockKey rowLockKey = new RowLockKey(getTabId(tableId), key);
    txnManager.getLockManager().lock(transaction.getTxnId(), rowLockKey, timeoutMillis);
    RowValue visible = getVisible(RowKey.of(getTabId(tableId), key));
    if(visible!=null){
      return RowCodec.decode(key,visible.payload);
    }
    return null;
  }

  public IndexBuildState getIndexBuildState(int tableId, int indexId) throws SQLException {
    return txnManager.getIndexBuildState(getTabId(tableId), indexId);
  }

  public void setIndexBuildState(int tableId, int indexId, IndexBuildState state) throws SQLException {
    txnManager.setIndexBuildState(getTabId(tableId), indexId, state);
  }

  public TabId getTabId(int tableId) {
    long epoch = transaction.getEpoch(tableId,k->getEpoch(tableId));
    return TabId.of(tableId, epoch);
  }

  private long getEpoch(int tableId) {
    TableEpochKey tableEpochKey = TableEpochKey.of(tableId);
    try {
      return this.txnManager.getStore().getLong(CF.META.getCfId(), tableEpochKey.toBytes())
          .orElse(0L);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  public void truncate(int tableId){
    transaction.truncate(tableId, k->getEpoch(tableId));
  }


}
