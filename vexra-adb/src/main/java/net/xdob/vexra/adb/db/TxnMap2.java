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
  private static final byte[] INDEX_VALUE_PAYLOAD =
      RowCodec.encode(ValueNull.INSTANCE);
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
   * 返回当前 H2 session 绑定�?ADB 事务�?   *
   * @return 当前事务对象
   */
  public Transaction2 getTransaction() {
    return transaction;
  }

  /**
   * 返回当前事务快照时间戳。
   *
   * @return 当前事务的 startTs
   */
  public long getStartTs() {
    return transaction.getStartTs();
  }

  /**
   * 返回事务管理器当前观察到的最新提交时间戳。
   *
   * @return 最新 committed ts
   */
  public long latestCommittedTs() {
    return txnManager.latestCommittedWatermarkTs();
  }

  /**
   * 返回事务管理器当前 store 派生缓存世代号。
   *
   * @return store 派生缓存世代号
   */
  public long storeDerivedCacheEpoch() {
    return txnManager.storeDerivedCacheEpoch();
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
   * 插入不存在的 row，并允许调用方复用已经计算过�?append fast path 判定�?   *
   * @param dataKey row key
   * @param row 待编码的 H2 row
   * @param skipAppendUniqueCheck 是否已确认可跳过 committed 版本唯一性扫�?   * @return 已存在的可见版本；不存在时返�?null
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
   * 批量追加插入一个已经编码好�?row value�?   *
   * <p>该方法服务于 JDBC bulk insert fast path：调用方已经完成 RowKey/RowValue
   * 构造，可以避免再次�?H2 Row 包装�?Value 后编码。对于无法使�?append hint �?key�?   * 仍会回退到当前事务快照下的可见性检查，保证重复主键不会被静默覆盖�?/p>
   *
   * @param dataKey row key
   * @param value 已编码的 row value
   * @return 已存在的可见版本；不存在时返�?null
   * @throws SQLException 可见性检查或写入失败时抛�?   */
  public RowValue putEncodedIfAbsent(DataKey dataKey, RowValue value)
      throws SQLException {
    if (canSkipAppendUniqueCheck(dataKey)) {
      this.put(dataKey, value, null);
      recordAppendHighWater(dataKey);
      return null;
    }
    RowValue old = getVisible(dataKey);
    if (old == null || old.deleted || old.payload == null) {
      this.put(dataKey, value, old);
      recordAppendHighWater(dataKey);
      return null;
    }
    return old;
  }

  /**
   * 写入已经编码好的数据 key，并复用调用方已经读取过的旧可见版本�?   *
   * <p>该入口用�?bulk insert 在完成整批主�?索引校验后一次性登记事务本地写集�?   * 调用方必须保�?{@code oldValue} 来自同一事务快照；这样提交和回滚仍然�?   * {@link Transaction2} �?write set / undo log 统一处理�?/p>
   *
   * @param dataKey 写入的逻辑 key，可以是 row key �?index key
   * @param value 已编码的写入�?   * @param oldValue 同一事务快照下的旧可见值，不存在时�?null
   * @throws SQLException 写入事务本地状态失败时抛出
   */
  public void putEncoded(DataKey dataKey, RowValue value, RowValue oldValue)
      throws SQLException {
    this.put(dataKey, value, oldValue);
  }

  /**
   * 写入 bulk append row，并登记当前事务内的 rowId 上界�?   *
   * <p>bulk insert 已经�?table 层完成批内重复主键检查和必要�?committed
   * 可见性检查；这里在复用旧值写入事务本�?write set 后，同步维护本事务内 append
   * high-water，让同一事务的后续追加批次可以继续走 fast path�?/p>
   *
   * @param dataKey row key
   * @param value 已编�?row value
   * @param oldValue 同一事务快照下的旧可见值；不存在时�?null
   * @throws SQLException 写入事务本地状态失败时抛出
   */
  public void putEncodedAppend(DataKey dataKey, RowValue value,
      RowValue oldValue) throws SQLException {
    this.put(dataKey, value, oldValue);
    recordAppendHighWater(dataKey);
  }

  /**
   * 写入已经由调用方完成唯一性检查的 bulk append row�?   *
   * <p>该入口不逐行更新 append high-water，供整批 append-safe 的批量写入使用；
   * 调用方需要在批次成功登记后调�?{@link #recordAppendHighWater(TabId, long)}
   * 一次性推进本事务内上界。失败路径会通过 savepoint rollback 清理 high-water�?/p>
   *
   * @param dataKey row key
   * @param value 已编�?row value
   * @param oldValue 同一事务快照下的旧可见值；append-safe 场景通常�?null
   * @throws SQLException 写入事务本地状态失败时抛出
   */
  public void putEncodedAppendAlreadyChecked(DataKey dataKey, RowValue value,
      RowValue oldValue) throws SQLException {
    this.put(dataKey, value, oldValue);
  }

  /**
   * 本地写入已经由调用方完成唯一性检查的 bulk append row�?   *
   * <p>该入口只允许本地 append-safe fast path 使用：调用方已经确认没有 region commit
   * coordinator、没有二级索引失败面，并且在调用前完成了所有可能失败的行编码。方法只登记
   * 事务本地 write-set / undo log / row-count delta，不访问底层 store�?/p>
   *
   * @param dataKey row key
   * @param value 已编�?row value
   */
  public void putEncodedAppendLocalAlreadyChecked(DataKey dataKey,
      RowValue value) {
    transaction.putLocal(dataKey, value, null);
  }

  /**
   * 判断当前事务是否可以跳过 append insert �?committed 版本扫描�?   *
   * <p>事务内已经写过相�?key 时必须回退到完整可见性检查，避免同一事务内重复主键被误判为可插入�?/p>
   */
  public boolean canSkipAppendUniqueCheck(DataKey dataKey) {
    if (dataKey == null || !dataKey.isRow()) {
      return false;
    }
    if (transaction.getLocalWrite(dataKey) != null) {
      return false;
    }
    if (canSkipByLocalAppendHighWater(dataKey)) {
      return true;
    }
    return txnManager.canSkipAppendUniqueCheck(dataKey);
  }

  /**
   * 判断整批 append row 是否可以跳过 committed 唯一性扫描�?   *
   * <p>调用方必须已经完成批内主键去重。该方法只在整批范围与当前事务本地写集不重叠时才
   * 返回 true，避免同一事务中先 update/delete 某个 rowId、随�?bulk insert 相同 rowId
   * 时被 append high-water 误判为可直接插入�?/p>
   *
   * @param tabId �?id �?epoch
   * @param minRowId 本批最�?rowId
   * @param maxRowId 本批最�?rowId
   * @return true 表示本批可以跳过 committed 可见性扫�?   */
  public boolean canSkipAppendUniqueChecks(TabId tabId, long minRowId,
      long maxRowId) {
    if (tabId == null || minRowId > maxRowId
        || hasLocalRowWriteInRange(tabId, minRowId, maxRowId)) {
      return false;
    }
    Long highWater = appendHighWater.get(tabId);
    if (highWater != null && minRowId > highWater) {
      return true;
    }
    return txnManager.canSkipAppendUniqueCheck(tabId, minRowId);
  }

  private boolean hasLocalRowWriteInRange(TabId tabId, long minRowId,
      long maxRowId) {
    for (DataKey key : transaction.getWriteSet().keySet()) {
      if (key != null && key.isRow() && tabId.equals(key.getTabID())) {
        long rowId = key.getRowId();
        if (rowId >= minRowId && rowId <= maxRowId) {
          return true;
        }
      }
    }
    return false;
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
    recordAppendHighWater(dataKey.getTabID(), dataKey.getRowId());
  }

  /**
   * 一次性推进当前事务内指定表的 append rowId 上界�?   *
   * @param tabId �?id �?epoch
   * @param rowId 已写入批次的最�?rowId
   */
  public void recordAppendHighWater(TabId tabId, long rowId) {
    if (tabId == null) {
      return;
    }
    Long highWater = appendHighWater.get(tabId);
    if (highWater == null || rowId > highWater) {
      appendHighWater.put(tabId, rowId);
    }
  }

  public void markStatementStart(){
    transaction.setStartTs(txnManager.lastCommitTs());
  }

  public RowValue getVisible(DataKey rowKey) throws SQLException {
    return txnManager.getVisible(transaction, rowKey);
  }

  /**
   * 读取当前事务可见行的单列值�?   *
   * <p>该入口服�?JDBC 主键点查单列投影快路径，允许底层�?committed store 命中时直接从 RowValue
   * 落盘字节�?payload 子区间解码目标列�?/p>
   *
   * @param rowKey �?key
   * @param columnId 目标列号
   * @return 可见列值；行不存在或已删除时返�?{@code null}
   * @throws SQLException 可见性读取失败时抛出
   */
  public TxnManager.VisibleColumnValue getVisibleColumn(RowKey rowKey,
      int columnId) throws SQLException {
    return txnManager.getVisibleColumn(transaction, rowKey, columnId);
  }

  /**
   * 复用指定版本读会话读取当前事务可见的单列值�?   *
   * @param readSession 与当前表修改世代匹配的版本读会话
   * @param rowKey �?key
   * @param columnId 目标列号
   * @return 可见列值；行不可见时返�?{@code null}
   * @throws SQLException 读取底层版本数据失败时抛�?   */
  public TxnManager.VisibleColumnValue getVisibleColumn(
      VersionReadSession readSession, RowKey rowKey, int columnId)
      throws SQLException {
    return txnManager.getVisibleColumn(transaction, rowKey, columnId,
        readSession);
  }

  /**
   * 判断 JDBC 点查 latest-committed 单列缓存是否仍可复用。
   *
   * @param rowKey 缓存行 key
   * @param cachedCommitTs 缓存值对应的 committed 版本
   * @param cacheWatermarkTs 缓存建立时观察到的全局提交水位
   * @return true 表示当前事务可安全复用该缓存值
   */
  public boolean canUseLatestCommittedColumnCache(RowKey rowKey,
      long cachedCommitTs, long cacheWatermarkTs,
      long cachedStoreDerivedCacheEpoch) {
    return txnManager.canUseLatestCommittedColumnCache(transaction, rowKey,
        cachedCommitTs, cacheWatermarkTs, cachedStoreDerivedCacheEpoch);
  }

  /**
   * 打开默认 CF 的版本读会话，供 JDBC 热路径按表修改世代复用�?   *
   * @return 新的版本读会�?   */
  public VersionReadSession openVersionReadSession() {
    return txnManager.openVersionReadSession();
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

  /**
   * 统计当前事务快照下指�?rowId 范围内可见的行数�?   *
   * @param prefixKey �?row 前缀
   * @param min 最�?rowId，null 表示无下�?   * @param max 最�?rowId，null 表示无上�?   * @return 可见且未删除的行�?   */
  public long countVisibleRows(PrefixKey prefixKey, Long min, Long max) {
    return txnManager.countVisibleRows(transaction, prefixKey, min, max);
  }

  /**
   * 复用指定版本读会话统计当前事务可见行数�?   *
   * @param readSession 与当前表修改世代匹配的版本读会话
   * @param prefixKey �?row 前缀
   * @param min 最�?rowId，null 表示无下�?   * @param max 最�?rowId，null 表示无上�?   * @return 可见且未删除的行�?   */
  public long countVisibleRows(VersionReadSession readSession,
      PrefixKey prefixKey, Long min, Long max) {
    return txnManager.countVisibleRows(transaction, prefixKey, min, max,
        readSession);
  }

  /**
   * 统计指定物理 row-version key 范围内的记录数。
   *
   * <p>该方法只服务于显式开启的 append-only benchmark profile。它统计物理版本记录数，
   * 不执行 MVCC 可见性、删除标记或本地写集合判断，因此不能作为通用 SQL COUNT 语义使用。</p>
   *
   * @param readSession 可复用读会话
   * @param prefixKey row key 前缀
   * @param min 最小 rowId，null 表示无下界
   * @param max 最大 rowId，null 表示无上界
   * @return 闭区间内的物理版本记录数
   */
  public long countPhysicalVersionRows(VersionReadSession readSession,
      PrefixKey prefixKey, Long min, Long max) {
    return txnManager.countPhysicalVersionRows(readSession, prefixKey, min,
        max);
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

    // 鍙熀浜庡綋鍓嶄簨鍔″彲瑙佽鍥惧垽�?
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
   * 在当前事务中登记二级索引 key 写入�?   *
   * <p>�?{@link #addIndexBatch(IndexPrefix, Collection)} 不同，该方法不直接写
   * committed version，而是写入事务本地 write set，使普通用户事务的 commit、rollback
   * �?savepoint 语义�?row 写入保持一致。调用方已经�?bulk insert 阶段完成主键�?   * 唯一索引和批内冲突校验；二级索引 key 又包�?rowId，因此这里不再逐个执行
   * {@code getVisible(indexKey)}，避免每个索引项都打开一次版本扫描�?/p>
   *
   * @param indexKeys 需要随当前事务提交的索�?key
   * @throws SQLException 可见性检查或事务写入失败时抛�?   */
  public void putIndexKeys(Collection<IndexKey> indexKeys) throws SQLException {
    for (IndexKey indexKey : indexKeys) {
      RowValue indexValue = new RowValue();
      indexValue.txnId = transaction.getTxnId();
      indexValue.commitTs = 0;
      indexValue.deleted = false;
      indexValue.payload = INDEX_VALUE_PAYLOAD;
      put(indexKey, indexValue, null);
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
