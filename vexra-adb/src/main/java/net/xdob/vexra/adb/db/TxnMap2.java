package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.*;
import org.h2.result.Row;
import org.h2.value.Value;
import org.h2.value.ValueNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TxnMap2 {
  static final Logger LOG = LoggerFactory.getLogger(TxnMap2.class);
  private  final TxnManager txnManager;
  private final Transaction2 transaction;
  private final List<AutoCloseable> resources = new ArrayList<>();
  private final Map<Integer, TabId> tabIdCache = new HashMap<>();
  private final Map<TabId, Long> appendHighWater = new HashMap<>();


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

  private void put(DataKey key, RowValue value, RowValue oldValue)
      throws SQLException {
    txnManager.put(transaction, key, value, oldValue);
  }

  public RowValue put(DataKey rowKey, Value row) throws SQLException {

    RowValue oldRowValue = getVisible(rowKey);
    RowValue rowValue = new RowValue();
    rowValue.txnId = transaction.getTxnId();
    rowValue.commitTs = 0;
    rowValue.deleted = false;
    rowValue.payload = RowCodec.encode(row);
    this.put(rowKey, rowValue, oldRowValue);
    return oldRowValue;
  }

  public RowValue putIfAbsent(DataKey dataKey, Value row) throws SQLException {
    return putIfAbsent(dataKey, row, canSkipAppendUniqueCheck(dataKey));
  }

  /**
   * 插入不存在的 row，并允许调用方复用已经计算过的 append fast path 判定。
   *
   * @param dataKey row key
   * @param row 待编码的 H2 row
   * @param skipAppendUniqueCheck 是否已确认可跳过 committed 版本唯一性扫描
   * @return 已存在的可见版本；不存在时返回 null
   */
  public RowValue putIfAbsent(DataKey dataKey, Value row,
      boolean skipAppendUniqueCheck) throws SQLException {
    if (skipAppendUniqueCheck) {
      RowValue value = new RowValue();
      value.txnId = transaction.getTxnId();
      value.commitTs = 0;
      value.deleted = false;
      value.payload = RowCodec.encode(row);
      this.put(dataKey, value, null);
      recordAppendHighWater(dataKey);
      return null;
    }

    RowValue old = getVisible(dataKey);
    if (old == null||old.deleted||old.payload== null) {
      RowValue value = new RowValue();
      value.txnId = transaction.getTxnId();
      value.commitTs = 0;
      value.deleted = false;
      value.payload = RowCodec.encode(row);
      this.put(dataKey, value, old);
      recordAppendHighWater(dataKey);
      return null;
    }
    return old;
  }

  /**
   * 批量追加插入一个已经编码好的 row value。
   *
   * <p>该方法服务于 JDBC bulk insert fast path：调用方已经完成 RowKey/RowValue
   * 构造，可以避免再次把 H2 Row 包装成 Value 后编码。对于无法使用 append hint 的 key，
   * 仍会回退到当前事务快照下的可见性检查，保证重复主键不会被静默覆盖。</p>
   *
   * @param dataKey row key
   * @param value 已编码的 row value
   * @return 已存在的可见版本；不存在时返回 null
   * @throws SQLException 可见性检查或写入失败时抛出
   */
  public RowValue putEncodedIfAbsent(DataKey dataKey, RowValue value)
      throws SQLException {
    if (canSkipAppendUniqueCheck(dataKey)) {
      this.put(dataKey, value, null);
      return null;
    }
    RowValue old = getVisible(dataKey);
    if (old == null || old.deleted || old.payload == null) {
      this.put(dataKey, value, old);
      return null;
    }
    return old;
  }

  /**
   * 写入已经编码好的数据 key，并复用调用方已经读取过的旧可见版本。
   *
   * <p>该入口用于 bulk insert 在完成整批主键/索引校验后一次性登记事务本地写集。
   * 调用方必须保证 {@code oldValue} 来自同一事务快照；这样提交和回滚仍然由
   * {@link Transaction2} 的 write set / undo log 统一处理。</p>
   *
   * @param dataKey 写入的逻辑 key，可以是 row key 或 index key
   * @param value 已编码的写入值
   * @param oldValue 同一事务快照下的旧可见值，不存在时为 null
   * @throws SQLException 写入事务本地状态失败时抛出
   */
  public void putEncoded(DataKey dataKey, RowValue value, RowValue oldValue)
      throws SQLException {
    this.put(dataKey, value, oldValue);
  }

  /**
   * 判断当前事务是否可以跳过 append insert 的 committed 版本扫描。
   *
   * <p>事务内已经写过相同 key 时必须回退到完整可见性检查，避免同一事务内重复主键被误判为可插入。</p>
   */
  public boolean canSkipAppendUniqueCheck(DataKey dataKey) {
    if (canSkipByLocalAppendHighWater(dataKey)) {
      return true;
    }
    return txnManager.canSkipAppendUniqueCheck(dataKey)
        && transaction.getLocalWrite(dataKey) == null;
  }

  private boolean canSkipByLocalAppendHighWater(DataKey dataKey) {
    if (dataKey == null || !dataKey.isRow()) {
      return false;
    }
    Long highWater = appendHighWater.get(dataKey.getTabID());
    return highWater != null && dataKey.getRowId() > highWater;
  }

  private void recordAppendHighWater(DataKey dataKey) {
    if (dataKey == null || !dataKey.isRow()) {
      return;
    }
    TabId tabId = dataKey.getTabID();
    Long highWater = appendHighWater.get(tabId);
    if (highWater == null || dataKey.getRowId() > highWater) {
      appendHighWater.put(tabId, dataKey.getRowId());
    }
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
      tabIdCache.clear();
      appendHighWater.clear();
      txnManager.getLockManager().unlockAll(transaction.getTxnId());
    }
  }

  public void setSavepoint(long savepointId){
    transaction.setSavepoint(String.valueOf(savepointId));
  }

  public void rollbackTo(long savepointId)  {
    try {
      txnManager.rollback(transaction, savepointId);
      appendHighWater.clear();
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
      tabIdCache.clear();
      appendHighWater.clear();
      txnManager.getLockManager().unlockAll(transaction.getTxnId());
    }
  }

  public void addIndexBatch(IndexPrefix indexPrefix, Collection<IndexKey> indexKeys) throws SQLException {
    txnManager.addIndexBatch(transaction, indexPrefix, indexKeys);
  }

  /**
   * 在当前事务中登记二级索引 key 写入。
   *
   * <p>与 {@link #addIndexBatch(IndexPrefix, Collection)} 不同，该方法不直接写
   * committed version，而是写入事务本地 write set，使普通用户事务的 commit、rollback
   * 和 savepoint 语义与 row 写入保持一致。</p>
   *
   * @param indexKeys 需要随当前事务提交的索引 key
   * @throws SQLException 可见性检查或事务写入失败时抛出
   */
  public void putIndexKeys(Collection<IndexKey> indexKeys) throws SQLException {
    for (IndexKey indexKey : indexKeys) {
      RowValue indexValue = new RowValue();
      indexValue.txnId = transaction.getTxnId();
      indexValue.commitTs = 0;
      indexValue.deleted = false;
      indexValue.payload = RowCodec.encode(ValueNull.INSTANCE);
      put(indexKey, indexValue, getVisible(indexKey));
    }
  }

  public Row lock(int tableId, long key, int timeoutMillis) throws SQLException {
    TabId tabId = getTabId(tableId);
    RowLockKey rowLockKey = new RowLockKey(tabId, key);
    txnManager.getLockManager().lock(transaction.getTxnId(), rowLockKey, timeoutMillis);
    RowValue visible = getVisible(RowKey.of(tabId, key));
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
    TabId cached = tabIdCache.get(tableId);
    if (cached != null && cached.epoch == epoch) {
      return cached;
    }
    TabId tabId = TabId.of(tableId, epoch);
    tabIdCache.put(tableId, tabId);
    return tabId;
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
    tabIdCache.remove(tableId);
    appendHighWater.clear();
  }


}
