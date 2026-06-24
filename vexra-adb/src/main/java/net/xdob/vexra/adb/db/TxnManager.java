package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.*;
import net.xdob.vexra.adb.key.*;
import net.xdob.vexra.adb.util.Utils;
import net.xdob.vexra.ldb.util.Slice;
import org.h2.api.ErrorCode;
import org.h2.message.DbException;
import org.h2.value.Value;
import org.h2.value.ValueBigint;
import org.h2.value.ValueInteger;
import org.h2.value.ValueNull;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class TxnManager {

  /**
   * committed row cache 默认只在支持内容世代号的 store 上跳过物理版本校验�?   *
   * <p>restore �?snapshot install 会推�?store 内容世代号并触发缓存失效；如果调用方需�?   * 恢复旧的每次命中都校�?committed version 行为，可以设�?   * {@code vexra.adb.rowCache.validateCommitted=true}�?/p>
   */
  private static final String TRUST_COMMITTED_ROW_CACHE_PROPERTY =
      "vexra.adb.rowCache.trustCommitted";
  private static final String VALIDATE_COMMITTED_ROW_CACHE_PROPERTY =
      "vexra.adb.rowCache.validateCommitted";
  private static final String RANGE_COUNT_SEGMENT_ENABLED_PROPERTY =
      "vexra.adb.rangeCount.segmentCount.enabled";
  private static final String RANGE_COUNT_SEGMENT_MIN_SEGMENTS_PROPERTY =
      "vexra.adb.rangeCount.segmentCount.minSegments";
  private static final String RANGE_COUNT_SEGMENT_COMPACT_DELTA_THRESHOLD_PROPERTY =
      "vexra.adb.rangeCount.segmentCount.compactDeltaThreshold";
  private static final int DEFAULT_ROW_COUNT_COMPACT_DELTA_THRESHOLD = 256;
  private static final int DEFAULT_RANGE_COUNT_SEGMENT_MIN_SEGMENTS = 4;
  private static final int RANGE_COUNT_SEGMENT_SIZE = 256;
  private static final int RAW_ROW_KEY_PREFIX_LENGTH = 21;
  private static final int RAW_VERSION_ROW_KEY_LENGTH = 30;
  private static final int RAW_ROW_ID_OFFSET = 13;
  private static final int RAW_COMMITTED_OFFSET = 21;
  private static final int RAW_VERSION_OFFSET = 22;
  private static final byte[] INDEX_VALUE_PAYLOAD =
      RowCodec.encode(ValueNull.INSTANCE);
  private static final List<String> LOCAL_REGION_IDS =
      Collections.singletonList("local");

  private TxnIdGenerator txnIdGen;
  private CommitTSGenerator tsGen;
  private DbStore store;
  private final LockManager lockManager = new LockManager();
  private final Object commitMutex = new Object();
  private final Map<Long, Transaction2> activeTransactions =
      new ConcurrentHashMap<>();
  private final Map<TabId, AtomicLong> maxRowIdHints =
      new ConcurrentHashMap<>();
  private final Map<DataKey, RowValue> committedRowCache =
      new ConcurrentHashMap<>();
  private final Map<TabId, AtomicLong> rowCountCache =
      new ConcurrentHashMap<>();
  private final Map<SegmentRowCountDeltaKey, AtomicLong>
      segmentRowCountCache = new ConcurrentHashMap<>();
  private final Map<TabId, Object> rowCountLoadLocks =
      new ConcurrentHashMap<>();
  private final boolean trustCommittedRowCache;
  private volatile long observedStoreContentEpoch;
  private volatile AdbRegionWriteGate regionWriteGate = AdbRegionWriteGate.NOOP;
  private volatile AdbRegionReadRouter regionReadRouter = AdbRegionReadRouter.NOOP;
  private volatile AdbRegionCommitCoordinator regionCommitCoordinator;
  private volatile AdbCrossRegionTxnGuard txnRegionGuard =
      AdbCrossRegionTxnGuard.noop();
  private volatile AdbTimestampProvider timestampProvider;
  private volatile AdbSqlDistributedScanRuntime sqlDistributedScanRuntime;
  private volatile AdbSqlDistributedWriteRuntime sqlDistributedWriteRuntime;
  private volatile AdbSqlDiagnosticRecorder sqlDiagnosticRecorder;
  private volatile boolean detailedSqlDiagnostics;
  private volatile long latestCommittedTs;

  public TxnManager(DbStore store) {
    this(store, defaultTrustCommittedRowCache(store));
  }

  TxnManager(DbStore store, boolean trustCommittedRowCache) {
    this.store = store;
    this.trustCommittedRowCache = trustCommittedRowCache;
    this.observedStoreContentEpoch = store.contentEpoch();
    this.txnIdGen = new TxnIdGenerator(store);
    this.tsGen = new CommitTSGenerator(store);
  }

  /**
   * 判断默认 committed row cache 是否可以跳过物理版本校验�?   *
   * <p>显式 {@code vexra.adb.rowCache.validateCommitted=true} 会强制保守校验；显式
   * {@code vexra.adb.rowCache.trustCommitted} 会按调用方配置执行。未显式配置时，只有能在
   * restore 后推进内容世代号�?store 才默认启�?trusted cache�?/p>
   *
   * @param store 当前事务管理器绑定的 store
   * @return 默认是否信任 committed row cache
   */
  private static boolean defaultTrustCommittedRowCache(DbStore store) {
    if (Boolean.getBoolean(VALIDATE_COMMITTED_ROW_CACHE_PROPERTY)) {
      return false;
    }
    String configured = System.getProperty(TRUST_COMMITTED_ROW_CACHE_PROPERTY);
    if (configured != null) {
      return Boolean.parseBoolean(configured);
    }
    return store != null && store.supportsContentEpoch();
  }

  public DbStore getStore() {
    return store;
  }

  /**
   * 使所有从底层 store 派生的进程内缓存失效�?   *
   * <p>该方法用�?restore、region snapshot 安装等会整体替换 store 可见内容的运维边界�?   * committed row cache 在压测模式下可以跳过物理版本校验，因�?restore 成功后必须主动清理；
   * row-count、segment row-count �?rowId hint 也同样来自持久化内容�?   * 需要一起失效�?/p>
   */
  public void invalidateStoreDerivedCaches() {
    committedRowCache.clear();
    rowCountCache.clear();
    segmentRowCountCache.clear();
    maxRowIdHints.clear();
    latestCommittedTs = 0L;
    observedStoreContentEpoch = store.contentEpoch();
  }

  public LockManager getLockManager() {
    return lockManager;
  }

  public AdbRegionWriteGate getRegionWriteGate() {
    return regionWriteGate;
  }

  /**
   * 设置 ADB region 写入 gate�?   *
   * <p>传入 null 会恢复为 no-op gate。gate �?commitTs 分配�?durable commit 前执行，
   * 用于分布�?region 模式下阻止缺少多数派的写入�?/p>
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
   * 设置 ADB region 读路由器�?   *
   * <p>传入 null 会恢复为 no-op router。router 在点读和扫描创建本地 cursor 前执行，
   * 用于分布�?region 模式下记录或校验读请求的 region 路由�?/p>
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
   * 设置 ADB region commit 协调器�?   *
   * <p>传入 null 会恢复为直接调用底层 store commit。启用后，事�?durable commit 会先
   * �?write set 路由�?region，再交由 coordinator 调用 region commit client�?/p>
   *
   * @param regionCommitCoordinator 新的 region commit 协调�?   */
  public void setRegionCommitCoordinator(
      AdbRegionCommitCoordinator regionCommitCoordinator) {
    this.regionCommitCoordinator = regionCommitCoordinator;
  }

  public AdbCrossRegionTxnGuard getTxnRegionGuard() {
    return txnRegionGuard;
  }

  /**
   * 设置事务提交前的生产范围 guard�?   *
   * <p>默认 no-op 以保持旧单机路径兼容。显式生产配置或 runtime session 可以安装�?guard�?   * 让没�?region coordinator 的本�?commit 也先通过生产能力边界；已安装 region coordinator
   * 时，真实 region 列表仍由 coordinator �?RPC 前校验�?/p>
   *
   * @param txnRegionGuard 事务 region guard；null 表示恢复 no-op
   */
  public void setTxnRegionGuard(AdbCrossRegionTxnGuard txnRegionGuard) {
    this.txnRegionGuard = txnRegionGuard == null
        ? AdbCrossRegionTxnGuard.noop() : txnRegionGuard;
  }

  /**
   * 设置 SQL 分布�?scan runtime�?   *
   * @param sqlDistributedScanRuntime SQL 分布�?scan runtime；null 表示关闭
   */
  public void setSqlDistributedScanRuntime(
      AdbSqlDistributedScanRuntime sqlDistributedScanRuntime) {
    this.sqlDistributedScanRuntime = sqlDistributedScanRuntime;
  }

  /**
   * 返回当前 SQL 分布�?scan runtime�?   *
   * @return runtime；未启用时为 null
   */
  public AdbSqlDistributedScanRuntime getSqlDistributedScanRuntime() {
    return sqlDistributedScanRuntime;
  }

  /**
   * 设置 SQL 分布式写�?runtime�?   *
   * @param sqlDistributedWriteRuntime SQL 分布式写�?runtime；null 表示关闭
   */
  public void setSqlDistributedWriteRuntime(
      AdbSqlDistributedWriteRuntime sqlDistributedWriteRuntime) {
    this.sqlDistributedWriteRuntime = sqlDistributedWriteRuntime;
  }

  /**
   * 返回当前 SQL 分布式写�?runtime�?   *
   * @return runtime；未启用时为 null
   */
  public AdbSqlDistributedWriteRuntime getSqlDistributedWriteRuntime() {
    return sqlDistributedWriteRuntime;
  }

  /**
   * 设置 SQL 诊断记录器�?   *
   * <p>recorder 只接收真�?ADB table engine 入口上报的轻量摘要，不参与事务提交�?   * 回滚或锁控制；传�?null 表示关闭当前 manager �?SQL 诊断�?/p>
   *
   * @param sqlDiagnosticRecorder SQL 诊断记录�?   */
  public void setSqlDiagnosticRecorder(
      AdbSqlDiagnosticRecorder sqlDiagnosticRecorder) {
    this.sqlDiagnosticRecorder = sqlDiagnosticRecorder;
    this.detailedSqlDiagnostics = sqlDiagnosticRecorder != null
        && Boolean.getBoolean("vexra.adb.sql.diagnostic.detail");
  }

  /**
   * 返回当前 SQL 诊断记录器�?   *
   * @return SQL 诊断记录器；未启用时返回 null
   */
  public AdbSqlDiagnosticRecorder getSqlDiagnosticRecorder() {
    return sqlDiagnosticRecorder;
  }

  /**
   * 记录一�?SQL 诊断事件�?   *
   * <p>该方法对业务路径�?best-effort：未启用 recorder 时直接返回，已启用时只做内存计数�?   * 不允许诊断链路改�?SQL 执行结果�?/p>
   *
   * @param event SQL 诊断事件
   */
  public void recordSqlDiagnostic(AdbSqlDiagnosticEvent event) {
    AdbSqlDiagnosticRecorder recorder = sqlDiagnosticRecorder;
    if (recorder != null && event != null) {
      try {
        recorder.record(event);
      } catch (RuntimeException ignored) {
        // 诊断链路必须是旁路能力，不能反向改变 SQL 执行结果�?
      }
    }
  }

  /**
   * 记录 SQL/table-engine 内部关键阶段耗时�?   *
   * <p>该方法与 SQL 事件诊断一样是 best-effort，任�?recorder 异常都不会反向影响事务�?   * 锁或底层存储结果�?/p>
   *
   * @param phase 阶段�?   * @param latencyNanos 阶段耗时，纳�?   */
  public void recordSqlPhase(String phase, long latencyNanos) {
    AdbSqlDiagnosticRecorder recorder = sqlDiagnosticRecorder;
    if (recorder != null && phase != null) {
      try {
        recorder.recordPhase(phase, latencyNanos);
      } catch (RuntimeException ignored) {
        // 诊断链路必须是旁路能力，不能反向改变 SQL 执行结果�?
      }
    }
  }

  private boolean detailedSqlDiagnostics() {
    return detailedSqlDiagnostics;
  }

  public AdbTimestampProvider getTimestampProvider() {
    return timestampProvider;
  }

  /**
   * 设置外部 timestamp provider�?   *
   * <p>传入 null 会恢复为本地 commitTs 计数器。启用后，新事务 startTs �?commitTs
   * 都由外部控制�?TSO 分配�?/p>
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
   * 返回当前活跃事务 startTs 快照�?   *
   * <p>该快照只包含本进程内已经 begin、但尚未 commit/rollback 成功的事务�?   * GC safe point 推进器使用它保护长事务，避免删除仍可能被快照读访问的历史版本�?/p>
   *
   * @return 活跃事务 startTs 的只读快�?   */
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

  // -------------------- �?鍒犻櫎鎿嶄綔 --------------------
  public void put(Transaction2 txn, DataKey key, RowValue value) throws SQLException {
    RowValue oldValue = getVisible(txn, key);
    put(txn, key, value, oldValue);
  }

  /**
   * 写入当前事务 intent，并复用 table/index 层已读到的旧可见版本�?   *
   * <p>该路径避免每�?SQL 写入重复打开版本扫描器；oldValue 必须来自同一事务快照�?   * 以保�?row-count delta、undo log 和冲突检测语义不变�?/p>
   */
  public void put(Transaction2 txn, DataKey key, RowValue value,
      RowValue oldValue) throws SQLException {
    if (regionCommitCoordinator != null) {
      store.writeBatch(s -> {
        txn.put(s, key, value, oldValue);
      });
      return;
    }
    txn.putLocal(key, value, oldValue);
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
    long baseRowCount = getCachedBaseRowCount(tId);
    long rowCountDelta = txn.getRowCountDelta(RowCountDeltaKey.of(tId));
    return baseRowCount + rowCountDelta;
  }

  /**
   * 预热指定表的 row-count 基线缓存�?   *
   * <p>该方法用于数据库打开或表对象恢复阶段，把 row-count base/delta 元数据扫描从首个业务
   * COUNT �?optimizer cost 读取前移。它只填充进程内缓存，不修改持久化数据；调用方可以安全地�?   * 表构造后执行�?/p>
   *
   * @param tId �?id �?epoch
   * @throws SQLException 底层 meta 扫描失败时抛�?   */
  public void prewarmRowCountCache(TabId tId) throws SQLException {
    if (tId == null) {
      return;
    }
    long started = System.nanoTime();
    Object loadLock = rowCountLoadLocks.computeIfAbsent(tId,
        ignored -> new Object());
    synchronized (loadLock) {
      if (rowCountCache.containsKey(tId)) {
        recordSqlPhase("ADB_ROW_COUNT_PREWARM_HIT",
            System.nanoTime() - started);
        return;
      }
      long loaded = getBaseRowCount(RowCountKey.of(tId));
      rowCountCache.put(tId, new AtomicLong(loaded));
      recordSqlPhase("ADB_ROW_COUNT_PREWARM",
          System.nanoTime() - started);
    }
  }

  private long getCachedBaseRowCount(TabId tId) throws SQLException {
    invalidateStoreDerivedCachesIfNeeded();
    long started = System.nanoTime();
    AtomicLong cached = rowCountCache.get(tId);
    if (cached != null) {
      try {
        return cached.get();
      } finally {
        recordSqlPhase("ADB_ROW_COUNT_CACHE_HIT",
            System.nanoTime() - started);
      }
    }
    Object loadLock = rowCountLoadLocks.computeIfAbsent(tId,
        ignored -> new Object());
    synchronized (loadLock) {
      AtomicLong loadedByPeer = rowCountCache.get(tId);
      if (loadedByPeer != null) {
        try {
          return loadedByPeer.get();
        } finally {
          recordSqlPhase("ADB_ROW_COUNT_CACHE_WAIT_HIT",
              System.nanoTime() - started);
        }
      }
      long loaded = getBaseRowCount(RowCountKey.of(tId));
      rowCountCache.put(tId, new AtomicLong(loaded));
      try {
        return loaded;
      } finally {
        recordSqlPhase("ADB_ROW_COUNT_CACHE_MISS",
            System.nanoTime() - started);
      }
    }
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
    long started = System.nanoTime();
    try {
      long rowCount = 0;
      RowValue visible = getVisibleBaseRowCount(key);
      long baseCommitTs = 0;

      if (visible != null && !visible.deleted && visible.payload != null) {
        rowCount = RowCodec.decode(visible.payload).getLong();
        baseCommitTs = visible.commitTs;
      }

      RowCountDeltaKey rowCountDeltaKey = RowCountDeltaKey.of(
          key.getTabKey());
      VersionRowCountDeltaKey startDeltaKey =
          VersionRowCountDeltaKey.of(key.getTabKey(), baseCommitTs);

      byte[] prefix = rowCountDeltaKey.toBytes();
      byte[] start = startDeltaKey.toBytes();
      byte[] end = KeyCodec.prefixEnd(prefix);
      int deltaCount = 0;
      long latestDeltaCommitTs = baseCommitTs;

      try (VersionScanSource scan = store.openVersionScanSource(
          CF.META.getCfId(), ScanDirection.FORWARD)) {
        scan.seekToRangeStart(start, end);

        while (scan.isValid() && KeyCodec.startsWith(scan.key(), prefix)) {
          VersionRowCountDeltaKey deltaKey =
              VersionRowCountDeltaKey.fromBytes(scan.key());
          if (deltaKey.getCommitTs() > baseCommitTs) {
            RowValue val = RowValue.decodeValue(scan.value());
            if (!val.deleted && val.payload != null) {
              rowCount += RowCodec.decode(val.payload).getLong();
            }
            deltaCount++;
            latestDeltaCommitTs = Math.max(latestDeltaCommitTs,
                deltaKey.getCommitTs());
          }
          scan.advance();
        }
        compactRowCountBaseIfNeeded(key.getTabKey(), rowCount,
            latestDeltaCommitTs, deltaCount);
        return rowCount;
      }
    } catch (Exception e) {
      if (e instanceof SQLException) {
        throw (SQLException) e;
      }
      throw new SQLException("Failed to resolve base row count", e);
    } finally {
      recordSqlPhase("ADB_ROW_COUNT_BASE_SCAN",
          System.nanoTime() - started);
    }
  }

  private void compactRowCountBaseIfNeeded(TabId tabId, long rowCount,
      long compactCommitTs, int deltaCount) {
    int threshold = rowCountCompactDeltaThreshold();
    if (threshold <= 0
        || deltaCount < threshold
        || compactCommitTs <= 0L) {
      return;
    }
    long started = System.nanoTime();
    try {
      VersionRowCountKey snapshotKey =
          VersionRowCountKey.of(tabId, compactCommitTs);
      RowValue snapshot = new RowValue();
      snapshot.deleted = false;
      snapshot.commitTs = compactCommitTs;
      snapshot.payload = RowCodec.encode(ValueBigint.get(rowCount));
      store.writeBatch(batch -> batch.put(CF.META.getCfId(),
          snapshotKey.toBytes(), RowValue.encodeValue(snapshot)));
    } catch (SQLException ignored) {
      // row-count base snapshot 是读后优化，失败时不能反向影响本�?COUNT 结果�?    } finally {
      recordSqlPhase("ADB_ROW_COUNT_BASE_COMPACT",
          System.nanoTime() - started);
    }
  }

  private static int rowCountCompactDeltaThreshold() {
    return Integer.getInteger("vexra.adb.rowCount.compactDeltaThreshold",
        DEFAULT_ROW_COUNT_COMPACT_DELTA_THRESHOLD);
  }

  /**
   * 返回 segment row-count base snapshot 的读后压实阈值�?   *
   * <p>该阈值只控制可�?segment range count 元数据链的读后优化；默认复用表级
   * row-count 压实阈值。配置为 0 或负数时关闭压实，但仍可读取已有 base�?/p>
   *
   * @return 触发压实所需的最�?delta 数量
   */
  private static int rangeCountSegmentCompactDeltaThreshold() {
    return Integer.getInteger(
        RANGE_COUNT_SEGMENT_COMPACT_DELTA_THRESHOLD_PROPERTY,
        DEFAULT_ROW_COUNT_COMPACT_DELTA_THRESHOLD);
  }


  public RowValue delete(Transaction2 txn, DataKey key) throws SQLException {
    RowValue result = getVisible(txn, key);
    if(result!=null) {
      if (regionCommitCoordinator != null) {
        store.writeBatch(s -> {
          txn.delete(s, key, result);
        });
      } else {
        txn.deleteLocal(key, result);
      }
      return result;
    }
    return null;
  }

  /**
   * 判断指定 row insert 是否可以跳过 committed 版本扫描�?   *
   * <p>�?hint 只在当前进程已经通过成功写入见过表内最�?rowId 后启用；未知表、重启恢复�?   * �?row key、低于或等于 hint �?key 都会回退到完整唯一性检查。因此它只优�?append
   * insert，不改变保守路径的正确性�?/p>
   */
  public boolean canSkipAppendUniqueCheck(DataKey key) {
    if (key == null || !key.isRow()) {
      return false;
    }
    return canSkipAppendUniqueCheck(key.getTabID(), key.getRowId());
  }

  /**
   * 判断指定表的 append insert 是否可以跳过 committed 唯一性扫描�?   *
   * <p>该入口供 bulk insert 在已经收集整批最�?rowId 后复用，避免为每一行都构�?   * RowKey 后再查一次全局 rowId hint。它只读取进程内成功提交后维护的最�?rowId
   * 上界，不改变事务状态；调用方仍需自行处理事务内本地写集冲突�?/p>
   *
   * @param tabId �?id �?epoch
   * @param rowId 待插�?rowId 或整批最�?rowId
   * @return true 表示�?rowId 一定大于当前进程已�?committed 上界
   */
  public boolean canSkipAppendUniqueCheck(TabId tabId, long rowId) {
    invalidateStoreDerivedCachesIfNeeded();
    if (tabId == null) {
      return false;
    }
    java.util.concurrent.atomic.AtomicLong hint = maxRowIdHints.get(tabId);
    return hint != null && rowId > hint.get();
  }

  /**
   * 记录当前进程已成功接收的 rowId 上界 hint�?   *
   * @param key row key
   */
  public void recordRowIdHint(DataKey key) {
    if (key == null || !key.isRow()) {
      return;
    }
    maxRowIdHints.computeIfAbsent(key.getTabID(),
        ignored -> new AtomicLong(Long.MIN_VALUE))
        .accumulateAndGet(key.getRowId(), Math::max);
  }

  private void recordRowIdHints(Collection<DataKey> keys) {
    if (keys == null || keys.isEmpty()) {
      return;
    }
    Map<TabId, Long> maxByTable = new HashMap<>();
    for (DataKey key : keys) {
      if (key == null || !key.isRow()) {
        continue;
      }
      TabId tabId = key.getTabID();
      Long previous = maxByTable.get(tabId);
      if (previous == null || key.getRowId() > previous) {
        maxByTable.put(tabId, key.getRowId());
      }
    }
    for (Map.Entry<TabId, Long> entry : maxByTable.entrySet()) {
      maxRowIdHints.computeIfAbsent(entry.getKey(),
          ignored -> new AtomicLong(Long.MIN_VALUE))
          .accumulateAndGet(entry.getValue(), Math::max);
    }
  }

  private void refreshRowCountCache(Map<RowCountDeltaKey, Long> deltas,
      Collection<Integer> invalidatedTableIds) {
    if (invalidatedTableIds != null && !invalidatedTableIds.isEmpty()) {
      rowCountCache.keySet().removeIf(
          tabId -> invalidatedTableIds.contains(tabId.id));
    }
    if (deltas == null || deltas.isEmpty()) {
      return;
    }
    for (Map.Entry<RowCountDeltaKey, Long> entry : deltas.entrySet()) {
      long delta = entry.getValue() == null ? 0L : entry.getValue();
      if (delta == 0L) {
        continue;
      }
      AtomicLong cached = rowCountCache.get(entry.getKey().getTabKey());
      if (cached != null) {
        cached.addAndGet(delta);
      }
    }
  }

  private void refreshSegmentRowCountCache(
      Map<SegmentRowCountDeltaKey, Long> deltas) {
    if (deltas == null || deltas.isEmpty()) {
      return;
    }
    for (Map.Entry<SegmentRowCountDeltaKey, Long> entry : deltas.entrySet()) {
      long delta = entry.getValue() == null ? 0L : entry.getValue();
      if (delta == 0L) {
        continue;
      }
      segmentRowCountCache.computeIfAbsent(entry.getKey(),
          ignored -> new AtomicLong(0L)).addAndGet(delta);
    }
  }

  /**
   * 批量写入已经完成回填�?rebuild 的索引项�?   *
   * <p>索引项写入后会立即作�?committed version 对后续事务可见，因此这里必须分配
   * 真实 commitTs，而不是复�?txnId。否则多�?backfill 时后续批次可能因�?commitTs
   * 大于读事�?startTs 而暂时不可见�?/p>
   *
   * @param txn 当前内部事务或建索引事务，仅用于保持既有调用签名
   * @param indexPrefix 索引前缀
   * @param indexKeys 需要写入的索引 key 集合
   * @throws SQLException 当底层批量写入失败时抛出
   */
  public void addIndexBatch(Transaction2 txn, IndexPrefix indexPrefix,
      Collection<IndexKey> indexKeys) throws SQLException {
    Objects.requireNonNull(txn, "txn == null");
    Objects.requireNonNull(indexPrefix, "indexPrefix == null");
    Objects.requireNonNull(indexKeys, "indexKeys == null");
    long commitTs = nextCommitTs();
    store.writeBatch(batch -> {
      //todo 绉婚櫎鑰佺殑绱㈠�?//      byte[] indexPrefixBytes = indexPrefix.toBytes();
//      byte[] prefixEnd = KeyCodec.prefixEnd(indexPrefixBytes);
//      batch.deleteRange(indexPrefixBytes, prefixEnd);
      for (IndexKey indexKey : indexKeys) {
        RowValue indexValue = new RowValue();
        indexValue.payload = INDEX_VALUE_PAYLOAD;
        VersionKey versionKey = VersionKey.of(indexKey, true, commitTs);
        indexValue.commitTs = commitTs;
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

  /**
   * 统计指定 rowId 范围内对当前事务可见的行数�?   *
   * <p>该路径服务于 {@code COUNT(*) WHERE primary_key BETWEEN ? AND ?}�?   * 与通用 {@link TableScanCursor} 不同，它只解�?RowValue 头部元数据，不复�?payload�?   * 同时在扫描结束后叠加当前事务尚未落到 store 的本�?write-set，保证本�?insert/delete
   * 和回滚语义与通用执行路径一致�?/p>
   *
   * @param txn 当前事务
   * @param prefixKey �?row 前缀
   * @param min 最�?rowId，null 表示无下�?   * @param max 最�?rowId，null 表示无上�?   * @return 当前事务快照下可见且未删除的行数
   */
  public long countVisibleRows(Transaction2 txn, PrefixKey prefixKey, Long min,
      Long max) {
    routeRangeRead(txn,
        tableScanStartKey(prefixKey, min), tableScanEndKey(prefixKey, max));

    byte[] tablePrefix = prefixKey.toBytes();
    if (!txn.mayHaveLocalRowWriteInRange(prefixKey.getTabID(), min, max)) {
      return countVisibleRowsWithoutLocalWrites(txn, prefixKey, tablePrefix,
          min, max);
    }
    Map<Long, RowValue> localRowWrites = localRowWritesInRange(txn,
        prefixKey.getTabID(), min, max);
    if (localRowWrites.isEmpty()) {
      return countVisibleRowsWithoutLocalWrites(txn, prefixKey, tablePrefix,
          min, max);
    }

    Long rawCount = countVisibleRowsWithLocalWritesRaw(txn, prefixKey,
        tablePrefix, min, max, localRowWrites);
    if (rawCount != null) {
      return rawCount;
    }
    return countVisibleRowsWithLocalWritesObject(txn, prefixKey, tablePrefix,
        min, max);
  }

  long countVisibleRows(Transaction2 txn, PrefixKey prefixKey, Long min,
      Long max, VersionReadSession readSession) {
    if (readSession == null) {
      return countVisibleRows(txn, prefixKey, min, max);
    }
    routeRangeRead(txn,
        tableScanStartKey(prefixKey, min), tableScanEndKey(prefixKey, max));

    byte[] tablePrefix = prefixKey.toBytes();
    if (txn.mayHaveLocalRowWriteInRange(prefixKey.getTabID(), min, max)) {
      return countVisibleRows(txn, prefixKey, min, max);
    }
    Long segmentCount = countVisibleRowsWithoutLocalWritesBySegments(txn,
        prefixKey, tablePrefix, min, max);
    if (segmentCount != null) {
      return segmentCount.longValue();
    }
    return countVisibleRowsWithoutLocalWritesReadSession(txn, prefixKey,
        tablePrefix, min, max, readSession);
  }

  private long countVisibleRowsWithLocalWritesObject(Transaction2 txn,
      PrefixKey prefixKey, byte[] tablePrefix, Long min, Long max) {
    Set<DataKey> localRowsCoveredByStoreScan = new HashSet<>();
    long count = 0L;

    try (VersionScanSource scan =
        store.openVersionScanSource(ScanDirection.FORWARD)) {
      seekToTableScanRange(scan, prefixKey, min, max);

      while (scan.isValid() && TableScanCursor.startsWith(scan.key(),
          tablePrefix)) {
        VersionKey versionKey = VersionKey.fromBytes(scan.key());
        DataKey dataKey = versionKey.toDataKey();
        byte[] rowPrefix = dataKey.toBytes();
        long rowId = dataKey.getRowId();

        if (!inRowIdRange(rowId, min, max)) {
          if (max != null && rowId > max) {
            break;
          }
          skipCurrentLogicalRow(scan, tablePrefix, rowPrefix);
          continue;
        }

        RowValue local = txn.getLocalWrite(dataKey);
        if (local != null) {
          localRowsCoveredByStoreScan.add(dataKey);
          skipCurrentLogicalRow(scan, tablePrefix, rowPrefix);
          if (isCountable(local)) {
            count++;
          }
          continue;
        }

        if (resolveVisibleCountableInCurrentLogicalRow(scan, rowPrefix,
            txn.getStartTs())) {
          count++;
        }
      }
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    return count + countLocalRowsMissingFromStore(txn, tablePrefix, min, max,
        localRowsCoveredByStoreScan);
  }

  private Long countVisibleRowsWithLocalWritesRaw(Transaction2 txn,
      PrefixKey prefixKey, byte[] tablePrefix, Long min, Long max,
      Map<Long, RowValue> localRowWrites) {
    long started = System.nanoTime();
    Set<Long> localRowsCoveredByStoreScan = new HashSet<>();
    long count = 0L;

    try {
      try (VersionScanSource scan =
          store.openVersionScanSource(ScanDirection.FORWARD)) {
        seekToTableScanRange(scan, prefixKey, min, max);

        while (scan.isValid()) {
          Slice rawKey = scan.keyView();
          if (!startsWith(rawKey, tablePrefix)) {
            break;
          }
          if (!isRawVersionRowKey(rawKey)) {
            return null;
          }
          long rowId = rawRowId(rawKey);

          if (!inRowIdRange(rowId, min, max)) {
            if (max != null && rowId > max) {
              break;
            }
            skipCurrentRawLogicalRow(scan, tablePrefix, rowId);
            continue;
          }

          RowValue local = localRowWrites.get(rowId);
          if (local != null) {
            localRowsCoveredByStoreScan.add(rowId);
            skipCurrentRawLogicalRow(scan, tablePrefix, rowId);
            if (isCountable(local)) {
              count++;
            }
            continue;
          }

          if (resolveVisibleCountableInCurrentRawLogicalRow(scan, rawKey,
              txn.getStartTs())) {
            count++;
          }
        }
      }
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      recordSqlPhase("ADB_RANGE_COUNT_VISIBLE_COUNT_RAW_LOCAL",
          System.nanoTime() - started);
    }

    return count + countLocalRowsMissingFromStore(localRowWrites,
        localRowsCoveredByStoreScan);
  }

  private long countVisibleRowsWithoutLocalWrites(Transaction2 txn,
      PrefixKey prefixKey, byte[] tablePrefix, Long min, Long max) {
    Long segmentCount = countVisibleRowsWithoutLocalWritesBySegments(txn,
        prefixKey, tablePrefix, min, max);
    if (segmentCount != null) {
      return segmentCount;
    }

    long started = System.nanoTime();
    try {
      return countVisibleRowsWithoutLocalWritesRawScan(txn, prefixKey,
          tablePrefix, min, max);
    } finally {
      recordSqlPhase("ADB_RANGE_COUNT_VISIBLE_COUNT_RAW",
          System.nanoTime() - started);
    }
  }

  private Long countVisibleRowsWithoutLocalWritesBySegments(
      Transaction2 txn, PrefixKey prefixKey, byte[] tablePrefix, Long min,
      Long max) {
    if (!rangeCountSegmentsEnabled() || min == null || max == null
        || min > max) {
      return null;
    }
    SegmentSpan span = fullyCoveredSegments(min, max);
    if (span == null
        || span.fullSegmentCount < rangeCountSegmentMinSegments()) {
      return null;
    }

    long started = System.nanoTime();
    long count = 0L;
    try {
      if (min < span.firstSegmentStart) {
        count += countVisibleRowsWithoutLocalWritesRawScan(txn, prefixKey,
            tablePrefix, min, span.firstSegmentStart - 1L);
      }

      count += getVisibleSegmentRowCount(prefixKey.getTabID(),
          span.firstSegmentId, span.lastSegmentId, txn.getStartTs());

      if (span.lastSegmentEnd < max) {
        count += countVisibleRowsWithoutLocalWritesRawScan(txn, prefixKey,
            tablePrefix, span.lastSegmentEnd + 1L, max);
      }
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      recordSqlPhase("ADB_RANGE_COUNT_SEGMENT_COUNT",
          System.nanoTime() - started);
    }
    return count;
  }

  private long countVisibleRowsWithoutLocalWritesRawScan(Transaction2 txn,
      PrefixKey prefixKey, byte[] tablePrefix, Long min, Long max) {
    long count = 0L;

    try (VersionScanSource scan =
        store.openVersionScanSource(ScanDirection.FORWARD)) {
      seekToTableScanRange(scan, prefixKey, min, max);

      while (scan.isValid()) {
        Slice rawKey = scan.keyView();
        if (!startsWith(rawKey, tablePrefix)) {
          break;
        }
        if (!isRawVersionRowKey(rawKey)) {
          break;
        }
        long rowId = rawRowId(rawKey);

        if (!inRowIdRange(rowId, min, max)) {
          if (max != null && rowId > max) {
            break;
          }
          skipCurrentRawLogicalRow(scan, tablePrefix, rowId);
          continue;
        }

        if (resolveVisibleCountableInCurrentRawLogicalRow(scan, rawKey,
            txn.getStartTs())) {
          count++;
        }
      }
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return count;
  }

  private long countVisibleRowsWithoutLocalWritesReadSession(Transaction2 txn,
      PrefixKey prefixKey, byte[] tablePrefix, Long min, Long max,
      VersionReadSession readSession) {
    long allocationStarted = AdbBenchmarkMain.benchmarkAllocationBytes();
    byte[] begin = tableScanStartKey(prefixKey, min);
    byte[] end = max == null ? tableScanClosedEndKey(prefixKey,
        Long.MAX_VALUE) : tableScanClosedEndKey(prefixKey, max);
    AdbBenchmarkMain.recordCurrentMixedStage(
        "plan.rangeCount.countVisible.readSession.boundKeys",
        allocationStarted, AdbBenchmarkMain.benchmarkAllocationBytes());
    allocationStarted = AdbBenchmarkMain.benchmarkAllocationBytes();
    RangeCountVisitor visitor = new RangeCountVisitor(tablePrefix, min, max,
        txn.getStartTs());
    AdbBenchmarkMain.recordCurrentMixedStage(
        "plan.rangeCount.countVisible.readSession.visitor",
        allocationStarted, AdbBenchmarkMain.benchmarkAllocationBytes());
    try {
      allocationStarted = AdbBenchmarkMain.benchmarkAllocationBytes();
      readSession.scanClosed(begin, end, visitor);
      AdbBenchmarkMain.recordCurrentMixedStage(
          "plan.rangeCount.countVisible.readSession.scanClosed",
          allocationStarted, AdbBenchmarkMain.benchmarkAllocationBytes());
    } catch (RuntimeException e) {
      throw e;
    }
    allocationStarted = AdbBenchmarkMain.benchmarkAllocationBytes();
    long count = visitor.count();
    AdbBenchmarkMain.recordCurrentMixedStage(
        "plan.rangeCount.countVisible.readSession.result",
        allocationStarted, AdbBenchmarkMain.benchmarkAllocationBytes());
    return count;
  }

  private static final class RangeCountVisitor
      implements VersionReadSession.EntryVisitor {
    private final byte[] tablePrefix;
    private final Long min;
    private final Long max;
    private final long startTs;
    private long currentRowId = Long.MIN_VALUE;
    private boolean currentRowResolved;
    private long count;

    private RangeCountVisitor(byte[] tablePrefix, Long min, Long max,
        long startTs) {
      this.tablePrefix = tablePrefix;
      this.min = min;
      this.max = max;
      this.startTs = startTs;
    }

    @Override
    public void visit(Slice rawKey, Slice value) {
      if (!startsWith(rawKey, tablePrefix) || !isRawVersionRowKey(rawKey)) {
        return;
      }
      long rowId = rawRowId(rawKey);
      if (!inRowIdRange(rowId, min, max)) {
        return;
      }
      if (rowId != currentRowId) {
        currentRowId = rowId;
        currentRowResolved = false;
      }
      if (currentRowResolved || !isRawCommittedVersion(rawKey)) {
        return;
      }
      long commitTs = rawCommitTs(rawKey);
      if (commitTs > startTs) {
        return;
      }
      int countableState = RowValue.countableStateView(value);
      if (countableState == RowValue.COUNTABLE_INVALID) {
        return;
      }
      if (countableState == RowValue.COUNTABLE_ROW) {
        count++;
      }
      currentRowResolved = true;
    }

    private long count() {
      return count;
    }
  }

  private long getVisibleSegmentRowCount(TabId tabId, long firstSegmentId,
      long lastSegmentId, long startTs) {
    long count = 0L;
    long segmentId = firstSegmentId;
    while (true) {
      if (startTs >= latestCommittedTs) {
        count += getLatestSegmentRowCount(tabId, segmentId);
      } else {
        count += getSegmentRowCount(tabId, segmentId, startTs);
      }
      if (segmentId == lastSegmentId) {
        return count;
      }
      segmentId++;
    }
  }

  /**
   * 读取最新快照下某个 segment 的行数，并在进程内缓存命中时避免访问 META CF�?   *
   * <p>缓存未命中时�?base + delta 读取路径，使冷启动或 cache miss 可以顺便触发
   * segment base snapshot 压实。缓存命中不强制压实，避免在线热路径引入额外写入�?/p>
   *
   * @param tabId �?id �?epoch
   * @param segmentId rowId 分段编号
   * @return 最新可见行�?   */
  private long getLatestSegmentRowCount(TabId tabId, long segmentId) {
    SegmentRowCountDeltaKey key = SegmentRowCountDeltaKey.of(tabId,
        segmentId);
    AtomicLong cached = segmentRowCountCache.get(key);
    if (cached != null) {
      return cached.get();
    }
    long loaded = getSegmentRowCount(tabId, segmentId, Long.MAX_VALUE);
    AtomicLong raced = segmentRowCountCache.putIfAbsent(key,
        new AtomicLong(loaded));
    return raced == null ? loaded : raced.get();
  }

  /**
   * 按指定快照读取单�?segment 的行数�?   *
   * <p>该方法先读取不晚�?{@code startTs} 的最�?base snapshot，再只叠�?base
   * 之后且不晚于快照�?delta。达到阈值时�?best-effort 写入新的 base snapshot�?   * 写入失败不会影响本次计数结果�?/p>
   *
   * @param tabId �?id �?epoch
   * @param segmentId rowId 分段编号
   * @param startTs 读事务快照时间戳
   * @return �?segment 在快照下的可见行�?   */
  private long getSegmentRowCount(TabId tabId, long segmentId,
      long startTs) {
    SegmentRowCountBase base = getVisibleBaseSegmentRowCount(tabId,
        segmentId, startTs);
    byte[] prefix = SegmentRowCountDeltaKey.of(tabId, segmentId).toBytes();
    byte[] start = VersionSegmentRowCountDeltaKey.of(tabId, segmentId,
        base.commitTs).toBytes();
    byte[] end = KeyCodec.prefixEnd(prefix);
    long count = base.rowCount;
    int deltaCount = 0;
    long latestDeltaCommitTs = base.commitTs;

    try (VersionScanSource scan = store.openVersionScanSource(CF.META.getCfId(),
        ScanDirection.FORWARD)) {
      scan.seekToRangeStart(start, end);
      while (scan.isValid() && KeyCodec.startsWith(scan.key(), prefix)) {
        byte[] key = scan.key();
        VersionSegmentRowCountDeltaKey deltaKey =
            VersionSegmentRowCountDeltaKey.fromBytes(key);
        if (deltaKey.getCommitTs() > startTs) {
          break;
        }
        if (deltaKey.getCommitTs() <= base.commitTs) {
          scan.advance();
          continue;
        }
        RowValue value = RowValue.decodeValue(scan.value());
        if (value != null && !value.deleted && value.payload != null
            && value.payload.length > 0) {
          count += RowCodec.decode(value.payload).getLong();
        }
        deltaCount++;
        latestDeltaCommitTs = Math.max(latestDeltaCommitTs,
            deltaKey.getCommitTs());
        scan.advance();
      }
      compactSegmentRowCountBaseIfNeeded(tabId, segmentId, count,
          latestDeltaCommitTs, deltaCount);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return count;
  }

  /**
   * 查找不晚于读快照的最�?segment row-count base�?   *
   * <p>base key 使用倒序 commitTs 编码，因此扫描到第一条不晚于 {@code startTs}
   * 的记录即可返回。旧库没�?base 时返�?0/0，让调用方退�?0 + delta 的兼容语义�?/p>
   *
   * @param tabId �?id �?epoch
   * @param segmentId rowId 分段编号
   * @param startTs 读事务快照时间戳
   * @return base 行数和对应提交时间戳
   */
  private SegmentRowCountBase getVisibleBaseSegmentRowCount(TabId tabId,
      long segmentId, long startTs) {
    byte[] prefix = SegmentRowCountKey.of(tabId, segmentId).toBytes();
    byte[] end = KeyCodec.prefixEnd(prefix);

    try (VersionScanSource scan = store.openVersionScanSource(CF.META.getCfId(),
        ScanDirection.FORWARD)) {
      scan.seekToRangeStart(prefix, end);
      while (scan.isValid() && KeyCodec.startsWith(scan.key(), prefix)) {
        VersionSegmentRowCountKey versionKey =
            VersionSegmentRowCountKey.fromBytes(scan.key());
        if (versionKey.getCommitTs() > startTs) {
          scan.advance();
          continue;
        }
        RowValue value = RowValue.decodeValue(scan.value());
        if (value == null || value.deleted || value.payload == null
            || value.payload.length == 0) {
          return new SegmentRowCountBase(0L, versionKey.getCommitTs());
        }
        return new SegmentRowCountBase(
            RowCodec.decode(value.payload).getLong(),
            versionKey.getCommitTs());
      }
      return SegmentRowCountBase.EMPTY;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * �?segment delta 链超过阈值后写入新的 base snapshot�?   *
   * <p>该方法只追加�?base，不删除�?delta，避免并发提交或旧快照读取场景下误删元数据�?   * 它是读后优化：失败会被忽略，只通过 SQL phase 诊断暴露耗时�?/p>
   *
   * @param tabId �?id �?epoch
   * @param segmentId rowId 分段编号
   * @param rowCount 已计算出的快照行�?   * @param compactCommitTs base 覆盖到的提交时间�?   * @param deltaCount 本次读取叠加�?delta 数量
   */
  private void compactSegmentRowCountBaseIfNeeded(TabId tabId, long segmentId,
      long rowCount, long compactCommitTs, int deltaCount) {
    int threshold = rangeCountSegmentCompactDeltaThreshold();
    if (threshold <= 0
        || deltaCount < threshold
        || compactCommitTs <= 0L) {
      return;
    }
    long started = System.nanoTime();
    try {
      VersionSegmentRowCountKey snapshotKey =
          VersionSegmentRowCountKey.of(tabId, segmentId, compactCommitTs);
      RowValue snapshot = new RowValue();
      snapshot.deleted = false;
      snapshot.commitTs = compactCommitTs;
      snapshot.payload = RowCodec.encode(ValueBigint.get(rowCount));
      store.writeBatch(batch -> batch.put(CF.META.getCfId(),
          snapshotKey.toBytes(), RowValue.encodeValue(snapshot)));
    } catch (SQLException ignored) {
      // segment base snapshot 是读后优化，失败不能影响本次 range count 结果�?    } finally {
      recordSqlPhase("ADB_RANGE_COUNT_SEGMENT_BASE_COMPACT",
          System.nanoTime() - started);
    }
  }

  private static Map<Long, RowValue> localRowWritesInRange(Transaction2 txn,
      TabId tabId, Long minRowId, Long maxRowId) {
    if (txn.getWriteSet().isEmpty()) {
      return Collections.emptyMap();
    }
    Map<Long, RowValue> localRows = new HashMap<>();
    for (Map.Entry<DataKey, RowValue> entry : txn.getWriteSet().entrySet()) {
      DataKey key = entry.getKey();
      if (key == null || !key.isRow() || !tabId.equals(key.getTabID())) {
        continue;
      }
      long rowId = key.getRowId();
      if (inRowIdRange(rowId, minRowId, maxRowId)) {
        localRows.put(rowId, entry.getValue());
      }
    }
    return localRows;
  }

  public RowValue getVisibleCommitted(Transaction2 txn, DataKey key) throws SQLException {
    if (key != null && key.isRow()) {
      return getVisibleCommittedRow(txn, key);
    }
    VersionResolver resolver = new DefaultVersionResolver(store);
    RowValue visible = resolver.getLatestCommittedBefore(key, txn.getStartTs());
    return visible;
  }

  public RowValue getLatestCommitted(Key key) throws SQLException {
    VersionResolver resolver = new DefaultVersionResolver(store);
    return resolver.getLatestCommitted(key);
  }

  public RowValue getVisible(Transaction2 txn, DataKey rowKey) throws SQLException {
    invalidateStoreDerivedCachesIfNeeded();
    if (detailedSqlDiagnostics()) {
      return getVisibleDetailed(txn, rowKey);
    }
    // 1. 鍏堢湅褰撳墠浜嬪姟鏈�?writeSet
    RowValue local = txn.getLocalWrite(rowKey);
    if (local != null) {
      return local;
    }

    regionReadRouter.routePointRead(txn, rowKey);

    RowValue cached = getVisibleCommittedFromCache(txn, rowKey);
    if (cached != null) {
      return cached.deleted ? null : cached;
    }

    // 2. �?committed �?Intent
    RowValue visible = this.getVisibleCommitted(txn, rowKey);

    long version = visible == null ? 0L : visible.commitTs;
    txn.recordRead(rowKey, version);
    return visible;
  }

  /**
   * 读取当前事务可见的单列值�?   *
   * <p>该入口只面向主键点查单列投影快路径：当命中底�?committed store 版本时，直接�?RowValue
   * 落盘字节�?payload 子区间解码目标列，避免先复制完整 payload。事�?read-set、region
   * 路由�?committed row cache 校验沿用 {@link #getVisible(Transaction2, DataKey)}
   * 的约束�?/p>
   *
   * @param txn 当前事务
   * @param rowKey �?key
   * @param columnId 目标列号
   * @return 可见列值；行不存在、被删除或没�?payload 时返�?{@code null}
   * @throws SQLException 读取 store �?region 路由失败时抛�?   */
  public VisibleColumnValue getVisibleColumn(Transaction2 txn, RowKey rowKey,
      int columnId) throws SQLException {
    return getVisibleColumn(txn, rowKey, columnId, null);
  }

  VisibleColumnValue getVisibleColumn(Transaction2 txn, RowKey rowKey,
      int columnId, VersionReadSession readSession) throws SQLException {
    invalidateStoreDerivedCachesIfNeeded();
    if (detailedSqlDiagnostics()) {
      RowValue rowValue = getVisibleDetailed(txn, rowKey);
      return visibleColumnFromRow(rowValue, columnId);
    }

    long allocationStarted = AdbBenchmarkMain.benchmarkAllocationBytes();
    RowValue local = txn.getLocalWrite(rowKey);
    AdbBenchmarkMain.recordCurrentMixedStage(
        "plan.pointLookup.visibleColumn.localWrite", allocationStarted,
        AdbBenchmarkMain.benchmarkAllocationBytes());
    if (local != null) {
      return visibleColumnFromRow(local, columnId);
    }

    allocationStarted = AdbBenchmarkMain.benchmarkAllocationBytes();
    regionReadRouter.routePointRead(txn, rowKey);
    AdbBenchmarkMain.recordCurrentMixedStage(
        "plan.pointLookup.visibleColumn.route", allocationStarted,
        AdbBenchmarkMain.benchmarkAllocationBytes());

    allocationStarted = AdbBenchmarkMain.benchmarkAllocationBytes();
    RowValue cached = getVisibleCommittedFromCache(txn, rowKey);
    AdbBenchmarkMain.recordCurrentMixedStage(
        "plan.pointLookup.visibleColumn.cache", allocationStarted,
        AdbBenchmarkMain.benchmarkAllocationBytes());
    if (cached != null) {
      allocationStarted = AdbBenchmarkMain.benchmarkAllocationBytes();
      try {
      return visibleColumnFromRow(cached, columnId);
      } finally {
        AdbBenchmarkMain.recordCurrentMixedStage(
            "plan.pointLookup.visibleColumn.decodeCached",
            allocationStarted, AdbBenchmarkMain.benchmarkAllocationBytes());
      }
    }

    allocationStarted = AdbBenchmarkMain.benchmarkAllocationBytes();
    VisibleColumnValue visible = readSession == null
        ? getVisibleCommittedColumn(txn, rowKey, columnId)
        : getVisibleCommittedColumn(txn, rowKey, columnId, readSession);
    AdbBenchmarkMain.recordCurrentMixedStage(
        "plan.pointLookup.visibleColumn.committedColumn", allocationStarted,
        AdbBenchmarkMain.benchmarkAllocationBytes());
    long version = visible == null ? 0L : visible.commitTs();
    allocationStarted = AdbBenchmarkMain.benchmarkAllocationBytes();
    txn.recordRead(rowKey, version);
    AdbBenchmarkMain.recordCurrentMixedStage(
        "plan.pointLookup.visibleColumn.recordRead", allocationStarted,
        AdbBenchmarkMain.benchmarkAllocationBytes());
    return visible;
  }

  VersionReadSession openVersionReadSession() {
    return store.openVersionReadSession();
  }

  private RowValue getVisibleDetailed(Transaction2 txn, DataKey rowKey)
      throws SQLException {
    long localStarted = System.nanoTime();
    RowValue local = txn.getLocalWrite(rowKey);
    long localElapsed = System.nanoTime() - localStarted;
    recordSqlPhase("ADB_VISIBLE_LOCAL_WRITE_CHECK", localElapsed);
    if (local != null) {
      recordSqlPhase("ADB_VISIBLE_LOCAL_WRITE_HIT", localElapsed);
      return local;
    }
    recordSqlPhase("ADB_VISIBLE_LOCAL_WRITE_MISS", localElapsed);

    long routeStarted = System.nanoTime();
    regionReadRouter.routePointRead(txn, rowKey);
    recordSqlPhase("ADB_VISIBLE_ROUTE_POINT_READ",
        System.nanoTime() - routeStarted);

    RowValue cached = getVisibleCommittedFromCacheDetailed(txn, rowKey);
    if (cached != null) {
      return cached.deleted ? null : cached;
    }

    RowValue visible = getVisibleCommittedDetailed(txn, rowKey);
    long version = visible == null ? 0L : visible.commitTs;
    recordReadVersion(txn, rowKey, version);
    return visible;
  }

  private RowValue getVisibleCommittedFromCache(Transaction2 txn,
      DataKey rowKey) throws SQLException {
    if (rowKey == null || !rowKey.isRow()) {
      return null;
    }
    RowValue cached = committedRowCache.get(rowKey);
    if (cached == null || cached.commitTs > txn.getStartTs()) {
      return null;
    }
    if (!trustCommittedRowCache
        && !cachedCommittedVersionExists(rowKey, cached.commitTs)) {
      committedRowCache.remove(rowKey, cached);
      return null;
    }
    txn.recordRead(rowKey, cached.commitTs);
    if (cached.rowKey == rowKey.getRowId()) {
      return cached;
    }
    return copyWithRowKey(cached, rowKey.getRowId());
  }

  private RowValue getVisibleCommittedFromCacheDetailed(Transaction2 txn,
      DataKey rowKey) throws SQLException {
    long started = System.nanoTime();
    boolean hit = false;
    try {
      if (rowKey == null || !rowKey.isRow()) {
        return null;
      }
      RowValue cached = committedRowCache.get(rowKey);
      if (cached == null || cached.commitTs > txn.getStartTs()) {
        return null;
      }
      if (!trustCommittedRowCache) {
        long validateStarted = System.nanoTime();
        boolean exists = cachedCommittedVersionExists(rowKey, cached.commitTs);
        recordSqlPhase("ADB_VISIBLE_COMMITTED_CACHE_VALIDATE",
            System.nanoTime() - validateStarted);
        if (!exists) {
          committedRowCache.remove(rowKey, cached);
          return null;
        }
      }
      recordReadVersion(txn, rowKey, cached.commitTs);
      hit = true;
      if (cached.rowKey == rowKey.getRowId()) {
        return cached;
      }
      return copyWithRowKey(cached, rowKey.getRowId());
    } finally {
      recordSqlPhase(hit ? "ADB_VISIBLE_COMMITTED_CACHE_HIT"
              : "ADB_VISIBLE_COMMITTED_CACHE_MISS",
          System.nanoTime() - started);
    }
  }

  private RowValue getVisibleCommittedDetailed(Transaction2 txn,
      DataKey rowKey) throws SQLException {
    long scanStarted = System.nanoTime();
    boolean sawNewerCommitted = false;
    try {
      byte[] prefix = rowKey instanceof RowKey
          ? ((RowKey) rowKey).versionScanPrefixBytes()
          : rowKey.toBytes();

      try (VersionScanSource scan =
               store.openVersionScanSource(ScanDirection.FORWARD)) {
        long seekStarted = System.nanoTime();
        // 点查只需要定位到 row prefix 起点；forward cursor 不消�?upperExclusive�?        scan.seekToRangeStart(prefix, null);
        recordSqlPhase("ADB_VISIBLE_STORE_SEEK",
            System.nanoTime() - seekStarted);

        while (scan.isValid()) {
          Slice rawKey = scan.keyView();
          if (rawKey == null || !startsWith(rawKey, prefix)) {
            return null;
          }

          long keyDecodeStarted = System.nanoTime();
          VersionKey versionKey = VersionKey.fromBytes(rawKey.copyBytes());
          recordSqlPhase("ADB_VISIBLE_VERSION_KEY_DECODE",
              System.nanoTime() - keyDecodeStarted);
          if (!versionKey.isCommited()) {
            recordSqlPhase("ADB_VISIBLE_INTENT_SKIP", 0L);
            long advanceStarted = System.nanoTime();
            scan.advance();
            recordSqlPhase("ADB_VISIBLE_STORE_ADVANCE",
                System.nanoTime() - advanceStarted);
            continue;
          }

          long rowDecodeStarted = System.nanoTime();
          RowValue rowValue = RowValue.decodeValueView(scan.valueView());
          recordSqlPhase("ADB_VISIBLE_ROW_VALUE_DECODE",
              System.nanoTime() - rowDecodeStarted);
          if (rowValue.commitTs <= txn.getStartTs()) {
            if (rowValue.deleted) {
              return null;
            }
            rowValue.rowKey = versionKey.getRowId();
            if (!sawNewerCommitted) {
              cacheCommittedVisible(rowKey, rowValue);
            }
            return rowValue;
          }
          sawNewerCommitted = true;

          long advanceStarted = System.nanoTime();
          scan.advance();
          recordSqlPhase("ADB_VISIBLE_STORE_ADVANCE",
              System.nanoTime() - advanceStarted);
        }
        return null;
      }
    } catch (Exception e) {
      if (e instanceof SQLException) {
        throw (SQLException) e;
      }
      throw new SQLException("Failed to get visible committed version", e);
    } finally {
      recordSqlPhase("ADB_VISIBLE_COMMITTED_STORE_SCAN",
          System.nanoTime() - scanStarted);
    }
  }

  private RowValue getVisibleCommittedRow(Transaction2 txn, DataKey rowKey)
      throws SQLException {
    byte[] prefix = rowKey instanceof RowKey
        ? ((RowKey) rowKey).versionScanPrefixBytes()
        : rowKey.toBytes();
    boolean sawNewerCommitted = false;
    try (VersionScanSource scan =
        store.openVersionScanSource(ScanDirection.FORWARD)) {
      // 点查只需要定位到 row prefix 起点；forward cursor 不消�?upperExclusive�?      scan.seekToRangeStart(prefix, null);

      while (scan.isValid()) {
        Slice rawKey = scan.keyView();
        if (rawKey == null || !startsWith(rawKey, prefix)) {
          return null;
        }
        if (!isRawVersionRowKey(rawKey)) {
          return null;
        }
        if (!isRawCommittedVersion(rawKey)) {
          scan.advance();
          continue;
        }

        long commitTs = rawCommitTs(rawKey);
        if (commitTs > txn.getStartTs()) {
          sawNewerCommitted = true;
          scan.advance();
          continue;
        }

        RowValue rowValue = RowValue.decodeValueView(scan.valueView());
        if (rowValue != null) {
          if (rowValue.deleted) {
            return null;
          }
          rowValue.rowKey = rowKey.getRowId();
          if (!sawNewerCommitted) {
            cacheCommittedVisible(rowKey, rowValue);
          }
          return rowValue;
        }
        sawNewerCommitted = true;

        scan.advance();
      }
      return null;
    } catch (Exception e) {
      if (e instanceof SQLException) {
        throw (SQLException) e;
      }
      throw new SQLException("Failed to get visible committed row", e);
    }
  }

  private VisibleColumnValue getVisibleCommittedColumn(Transaction2 txn,
      RowKey rowKey, int columnId) throws SQLException {
    long allocationStarted = AdbBenchmarkMain.benchmarkAllocationBytes();
    byte[] prefix = rowKey.versionScanPrefixBytes();
    AdbBenchmarkMain.recordCurrentMixedStage(
        "plan.pointLookup.visibleColumn.committedColumn.prefix",
        allocationStarted, AdbBenchmarkMain.benchmarkAllocationBytes());
    boolean latestCommitted = true;
    VersionScanSource scan = null;
    try {
      allocationStarted = AdbBenchmarkMain.benchmarkAllocationBytes();
      scan = store.openVersionScanSource(ScanDirection.FORWARD);
      AdbBenchmarkMain.recordCurrentMixedStage(
          "plan.pointLookup.visibleColumn.committedColumn.openScan",
          allocationStarted, AdbBenchmarkMain.benchmarkAllocationBytes());
      allocationStarted = AdbBenchmarkMain.benchmarkAllocationBytes();
      scan.seekToRangeStart(prefix, null);
      AdbBenchmarkMain.recordCurrentMixedStage(
          "plan.pointLookup.visibleColumn.committedColumn.seek",
          allocationStarted, AdbBenchmarkMain.benchmarkAllocationBytes());

      while (scan.isValid()) {
        allocationStarted = AdbBenchmarkMain.benchmarkAllocationBytes();
        Slice rawKey = scan.keyView();
        AdbBenchmarkMain.recordCurrentMixedStage(
            "plan.pointLookup.visibleColumn.committedColumn.keyView",
            allocationStarted, AdbBenchmarkMain.benchmarkAllocationBytes());
        if (rawKey == null || !startsWith(rawKey, prefix)) {
          return null;
        }
        if (!isRawVersionRowKey(rawKey)) {
          return null;
        }
        if (!isRawCommittedVersion(rawKey)) {
          scan.advance();
          continue;
        }

        long commitTs = rawCommitTs(rawKey);
        if (commitTs > txn.getStartTs()) {
          latestCommitted = false;
          scan.advance();
          continue;
        }

        allocationStarted = AdbBenchmarkMain.benchmarkAllocationBytes();
        Slice encoded = scan.valueView();
        AdbBenchmarkMain.recordCurrentMixedStage(
            "plan.pointLookup.visibleColumn.committedColumn.valueView",
            allocationStarted, AdbBenchmarkMain.benchmarkAllocationBytes());
        if (encoded == null || encoded.length() == 0
            || RowValue.isDeletedView(encoded)) {
          return null;
        }
        int payloadLength = RowValue.payloadLengthView(encoded);
        if (payloadLength <= 0) {
          return null;
        }
        allocationStarted = AdbBenchmarkMain.benchmarkAllocationBytes();
        Value value = RowCodec.decodeColumn(encoded.getRawArray(),
            encoded.getRawOffset() + RowValue.payloadOffset(), payloadLength,
            columnId);
        AdbBenchmarkMain.recordCurrentMixedStage(
            "plan.pointLookup.visibleColumn.committedColumn.decode",
            allocationStarted, AdbBenchmarkMain.benchmarkAllocationBytes());
        allocationStarted = AdbBenchmarkMain.benchmarkAllocationBytes();
        VisibleColumnValue visibleColumn = new VisibleColumnValue(commitTs,
            value, latestCommitted);
        AdbBenchmarkMain.recordCurrentMixedStage(
            "plan.pointLookup.visibleColumn.committedColumn.result",
            allocationStarted, AdbBenchmarkMain.benchmarkAllocationBytes());
        return visibleColumn;
      }
      return null;
    } catch (Exception e) {
      if (e instanceof SQLException) {
        throw (SQLException) e;
      }
      throw new SQLException("Failed to get visible committed column", e);
    } finally {
      if (scan != null) {
        allocationStarted = AdbBenchmarkMain.benchmarkAllocationBytes();
        try {
          scan.close();
        } catch (Exception e) {
          if (e instanceof SQLException) {
            throw (SQLException) e;
          }
          throw new SQLException("Failed to close visible committed column scan",
              e);
        }
        AdbBenchmarkMain.recordCurrentMixedStage(
            "plan.pointLookup.visibleColumn.committedColumn.close",
            allocationStarted, AdbBenchmarkMain.benchmarkAllocationBytes());
      }
    }
  }

  private VisibleColumnValue getVisibleCommittedColumn(Transaction2 txn,
      RowKey rowKey, int columnId, VersionReadSession readSession)
      throws SQLException {
    long allocationStarted = AdbBenchmarkMain.benchmarkAllocationBytes();
    byte[] prefix = rowKey.versionScanPrefixBytes();
    AdbBenchmarkMain.recordCurrentMixedStage(
        "plan.pointLookup.visibleColumn.committedColumn.prefix",
        allocationStarted, AdbBenchmarkMain.benchmarkAllocationBytes());
    byte[] end = rowVersionClosedEnd(prefix);
    VisibleColumnScanVisitor visitor = new VisibleColumnScanVisitor(prefix,
        txn.getStartTs(), columnId);
    try {
      allocationStarted = AdbBenchmarkMain.benchmarkAllocationBytes();
      readSession.scanClosed(prefix, end, visitor);
      AdbBenchmarkMain.recordCurrentMixedStage(
          "plan.pointLookup.visibleColumn.committedColumn.readSessionScan",
          allocationStarted, AdbBenchmarkMain.benchmarkAllocationBytes());
      return visitor.result();
    } catch (RuntimeException e) {
      throw new SQLException("Failed to get visible committed column", e);
    }
  }

  private static byte[] rowVersionClosedEnd(byte[] prefix) {
    byte[] data = Arrays.copyOf(prefix, RAW_VERSION_ROW_KEY_LENGTH);
    Arrays.fill(data, prefix.length, data.length, (byte) 0xff);
    return data;
  }

  private static VisibleColumnValue visibleColumnFromRow(RowValue rowValue,
      int columnId) {
    if (rowValue == null || rowValue.deleted || rowValue.payload == null
        || rowValue.payload.length == 0) {
      return null;
    }
    return new VisibleColumnValue(rowValue.commitTs,
        RowCodec.decodeColumn(rowValue.payload, columnId), true);
  }

  private static final class VisibleColumnScanVisitor
      implements VersionReadSession.EntryVisitor {
    private final byte[] prefix;
    private final long startTs;
    private final int columnId;
    private boolean latestCommitted = true;
    private boolean done;
    private VisibleColumnValue result;

    private VisibleColumnScanVisitor(byte[] prefix, long startTs,
        int columnId) {
      this.prefix = prefix;
      this.startTs = startTs;
      this.columnId = columnId;
    }

    @Override
    public void visit(Slice rawKey, Slice encoded) {
      if (done) {
        return;
      }
      if (rawKey == null || !startsWith(rawKey, prefix)
          || !isRawVersionRowKey(rawKey)) {
        done = true;
        return;
      }
      if (!isRawCommittedVersion(rawKey)) {
        return;
      }
      long commitTs = rawCommitTs(rawKey);
      if (commitTs > startTs) {
        latestCommitted = false;
        return;
      }
      if (encoded == null || encoded.length() == 0
          || RowValue.isDeletedView(encoded)) {
        done = true;
        return;
      }
      int payloadLength = RowValue.payloadLengthView(encoded);
      if (payloadLength <= 0) {
        done = true;
        return;
      }
      Value value = RowCodec.decodeColumn(encoded.getRawArray(),
          encoded.getRawOffset() + RowValue.payloadOffset(), payloadLength,
          columnId);
      result = new VisibleColumnValue(commitTs, value, latestCommitted);
      done = true;
    }

    private VisibleColumnValue result() {
      return result;
    }
  }

  private void recordReadVersion(Transaction2 txn, DataKey key, long version) {
    long started = System.nanoTime();
    try {
      txn.recordRead(key, version);
    } finally {
      recordSqlPhase("ADB_VISIBLE_READ_SET_RECORD",
          System.nanoTime() - started);
    }
  }

  private boolean cachedCommittedVersionExists(DataKey rowKey, long commitTs)
      throws SQLException {
    byte[] versionKey = rowKey instanceof RowKey
        ? VersionRowKey.committedBytes((RowKey) rowKey, commitTs)
        : VersionKey.of(rowKey, true, commitTs).toBytes();
    return store.get(versionKey) != null;
  }

  /**
   * 检查底�?store 内容世代号并按需清理派生缓存�?   *
   * <p>该方法用于覆盖直�?{@code DbStore.restore(...)} 等绕过运维桥接层的内容替换路径�?   * 支持 content epoch �?store �?restore 成功后递增世代号；事务管理器下一次读�?   * row-count �?append hint 使用前会发现变化并清空旧缓存�?/p>
   */
  private void invalidateStoreDerivedCachesIfNeeded() {
    long currentEpoch = store.contentEpoch();
    if (currentEpoch == observedStoreContentEpoch) {
      return;
    }
    synchronized (this) {
      currentEpoch = store.contentEpoch();
      if (currentEpoch != observedStoreContentEpoch) {
        committedRowCache.clear();
        rowCountCache.clear();
        segmentRowCountCache.clear();
        maxRowIdHints.clear();
        latestCommittedTs = 0L;
        observedStoreContentEpoch = currentEpoch;
      }
    }
  }

  /**
   * 回填�?store 读出�?committed 可见行�?   *
   * <p>只缓存真实存在的 row 版本；deleted/null/索引 key 仍走原路径。缓存值复制一份，避免后续调用方修�?   * {@link RowValue#rowKey} 时影响共享缓存�?/p>
   */
  private void cacheCommittedVisible(DataKey key, RowValue visible) {
    if (key == null || !key.isRow() || visible == null || visible.deleted
        || visible.payload == null) {
      return;
    }
    RowValue cached = visible.rowKey == key.getRowId()
        ? copyWithRowKey(visible, visible.rowKey)
        : copyWithRowKey(visible, key.getRowId());
    committedRowCache.compute(key, (ignored, existing) ->
        existing != null && existing.commitTs > cached.commitTs
            ? existing : cached);
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


      // 濡傛灉鐗堟湰娌″彉 �?OK
      if (currentVersion == readVersion) {
        continue;
      }

      // 濡傛灉鍙樹簡锛岃鍒ゆ柇鏄笉鏄€滆嚜宸卞啓鐨勨€?
      if (txn.hasWritten(key)) {
        continue;
      }

      // 鍚﹀垯鎵嶆槸鍐茬�?
      throw DbException.get(ErrorCode.CONCURRENT_UPDATE_1, key.toString());
    }
  }


  // -------------------- 鎻愪氦浜嬪姟 --------------------
  public void commit(Transaction2 txn) throws SQLException {
    final long commitTs;
    final Collection<DataKey> writeKeys;
    final Map<RowCountDeltaKey, Long> rowCountDeltas;
    final Map<SegmentRowCountDeltaKey, Long> segmentRowCountDeltas;
    final Map<Integer, Long> tableEpochUpdates;

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

      long prepareStarted = System.nanoTime();
      try {
        validate(txn);
        Map<DataKey, RowValue> writeSet = txn.getWriteSet();
        writeKeys = writeSet.isEmpty()
            ? Collections.emptyList() : new ArrayList<>(writeSet.keySet());
        validateLocalProductionCommit(writeKeys);
        regionWriteGate.beforeCommit(txn, writeKeys);
        commitTs = nextCommitTs();
        txn.setState(TxnState.COMMITTING);
      } finally {
        recordSqlPhase("ADB_COMMIT_PREPARE",
            System.nanoTime() - prepareStarted);
      }

      rowCountDeltas = snapshotRowCountDeltas(txn);
      segmentRowCountDeltas = snapshotSegmentRowCountDeltas(txn);
      tableEpochUpdates = snapshotTableEpochUpdates(txn);
    }

    try {
      long rowCountMetaStarted = System.nanoTime();
      List<Meta> metas = buildCommitMetas(rowCountDeltas,
          segmentRowCountDeltas, tableEpochUpdates, commitTs);
      recordSqlPhase("ADB_COMMIT_ROW_COUNT_META",
          System.nanoTime() - rowCountMetaStarted);

      long writeStarted = System.nanoTime();
      try {
        commitLocalOrRemote(txn, commitTs, writeKeys, metas);
      } finally {
        recordSqlPhase("ADB_COMMIT_WRITE",
            System.nanoTime() - writeStarted);
      }

      long postCommitStarted = System.nanoTime();
      refreshCommittedRowCache(txn, commitTs, writeKeys);
      refreshRowCountCache(rowCountDeltas, tableEpochUpdates.keySet());
      refreshSegmentRowCountCache(segmentRowCountDeltas);
      latestCommittedTs = Math.max(latestCommittedTs, commitTs);
      txn.afterCommitSuccess(commitTs);
      recordRowIdHints(writeKeys);
      activeTransactions.remove(txn.getTxnId());
      recordSqlPhase("ADB_COMMIT_POST_REFRESH",
          System.nanoTime() - postCommitStarted);
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

  private void validateLocalProductionCommit(Collection<DataKey> writeKeys)
      throws SQLException {
    if (writeKeys.isEmpty() || regionCommitCoordinator != null) {
      return;
    }
    txnRegionGuard.beforeCommit("local-commit", -1, LOCAL_REGION_IDS);
  }

  private void commitLocalOrRemote(Transaction2 txn, long commitTs,
      Collection<DataKey> writeKeys, List<Meta> metas) throws SQLException {
    AdbRegionCommitCoordinator coordinator = regionCommitCoordinator;
    if (coordinator != null && !writeKeys.isEmpty()) {
      coordinator.commitAsync(txn, commitTs, writeKeys, metas).join();
      return;
    }
    commitLocalDirect(txn, commitTs, metas);
  }

  private void commitLocalDirect(Transaction2 txn, long commitTs,
      List<Meta> metas) throws SQLException {
    store.writeBatch(batch -> {
      for (Map.Entry<DataKey, RowValue> entry : txn.getWriteSet().entrySet()) {
        DataKey key = entry.getKey();
        byte[] versionKey = key instanceof RowKey
            ? VersionRowKey.committedBytes((RowKey) key, commitTs)
            : VersionKey.of(key, true, commitTs).toBytes();
        batch.put(versionKey,
            RowValue.encodeValue(entry.getValue(), commitTs));
      }
      if (metas != null && !metas.isEmpty()) {
        for (Meta meta : metas) {
          batch.put(CF.META.getCfId(), meta.getKey(), meta.getValue());
        }
      }
    });
  }

  private static Map<RowCountDeltaKey, Long> snapshotRowCountDeltas(
      Transaction2 txn) {
    if (!txn.hasRowCountDeltas()) {
      return Collections.emptyMap();
    }
    LinkedHashMap<RowCountDeltaKey, Long> result = null;
    for (Map.Entry<RowCountDeltaKey, AtomicLong> entry
        : txn.rowCountDeltasForCommit().entrySet()) {
      RowCountDeltaKey key = entry.getKey();
      AtomicLong value = entry.getValue();
      long delta = value == null ? 0L : value.get();
      if (key != null && delta != 0L) {
        if (result == null) {
          result = new LinkedHashMap<>();
        }
        result.put(key, delta);
      }
    }
    return result == null ? Collections.emptyMap() : result;
  }

  private static Map<SegmentRowCountDeltaKey, Long>
      snapshotSegmentRowCountDeltas(Transaction2 txn) {
    if (!txn.hasSegmentRowCountDeltas()) {
      return Collections.emptyMap();
    }
    LinkedHashMap<SegmentRowCountDeltaKey, Long> result = null;
    for (Map.Entry<SegmentRowCountDeltaKey, AtomicLong> entry
        : txn.segmentRowCountDeltasForCommit().entrySet()) {
      SegmentRowCountDeltaKey key = entry.getKey();
      AtomicLong value = entry.getValue();
      long delta = value == null ? 0L : value.get();
      if (key != null && delta != 0L) {
        if (result == null) {
          result = new LinkedHashMap<>();
        }
        result.put(key, delta);
      }
    }
    return result == null ? Collections.emptyMap() : result;
  }

  private static Map<Integer, Long> snapshotTableEpochUpdates(
      Transaction2 txn) {
    if (!txn.hasTableEpochs()) {
      return Collections.emptyMap();
    }
    LinkedHashMap<Integer, Long> result = null;
    for (Map.Entry<Integer, Epoch> entry
        : txn.tableEpochsForCommit().entrySet()) {
      Integer tableId = entry.getKey();
      Epoch epoch = entry.getValue();
      if (tableId != null && epoch != null && epoch.intent) {
        if (result == null) {
          result = new LinkedHashMap<>();
        }
        result.put(tableId, epoch.epoch);
      }
    }
    return result == null ? Collections.emptyMap() : result;
  }

  private static List<Meta> buildCommitMetas(
      Map<RowCountDeltaKey, Long> rowCountDeltas,
      Map<SegmentRowCountDeltaKey, Long> segmentRowCountDeltas,
      Map<Integer, Long> tableEpochUpdates, long commitTs) {
    if (rowCountDeltas.isEmpty() && segmentRowCountDeltas.isEmpty()
        && tableEpochUpdates.isEmpty()) {
      return Collections.emptyList();
    }
    List<Meta> metas = new ArrayList<>(
        rowCountDeltas.size() + segmentRowCountDeltas.size()
            + tableEpochUpdates.size());
    for (Map.Entry<RowCountDeltaKey, Long> entry : rowCountDeltas.entrySet()) {
      RowCountDeltaKey key = entry.getKey();
      long rowCountDelta = entry.getValue();
      VersionRowCountDeltaKey tableStatsKey =
          VersionRowCountDeltaKey.of(key.getTabKey(), commitTs);

      RowValue tableStats = new RowValue();
      tableStats.deleted = false;
      tableStats.payload = RowCodec.encode(ValueBigint.get(rowCountDelta));
      tableStats.commitTs = commitTs;
      metas.add(Meta.of(tableStatsKey.toBytes(),
          RowValue.encodeValue(tableStats)));
    }

    for (Map.Entry<SegmentRowCountDeltaKey, Long> entry
        : segmentRowCountDeltas.entrySet()) {
      SegmentRowCountDeltaKey key = entry.getKey();
      long rowCountDelta = entry.getValue();
      VersionSegmentRowCountDeltaKey segmentStatsKey =
          VersionSegmentRowCountDeltaKey.of(key.getTabKey(),
              key.getSegmentId(), commitTs);

      RowValue segmentStats = new RowValue();
      segmentStats.deleted = false;
      segmentStats.payload = RowCodec.encode(ValueBigint.get(rowCountDelta));
      segmentStats.commitTs = commitTs;
      metas.add(Meta.of(segmentStatsKey.toBytes(),
          RowValue.encodeValue(segmentStats)));
    }

    for (Map.Entry<Integer, Long> entry : tableEpochUpdates.entrySet()) {
      TableEpochKey tableEpochKey = TableEpochKey.of(entry.getKey());
      metas.add(Meta.of(tableEpochKey.toBytes(),
          Utils.encodeLong(entry.getValue())));
    }
    return metas;
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

  private static RowValue copyForCommit(RowValue source, long commitTs) {
    RowValue copy = new RowValue();
    copy.txnId = source.txnId;
    copy.deleted = source.deleted;
    copy.payload = source.payload;
    copy.rowKey = source.rowKey;
    copy.commitTs = commitTs;
    return copy;
  }

  private void refreshCommittedRowCache(Transaction2 txn, long commitTs,
      Collection<DataKey> writeKeys) {
    for (DataKey key : writeKeys) {
      if (key == null || !key.isRow()) {
        continue;
      }
      RowValue value = txn.getWriteSet().get(key);
      if (value == null) {
        continue;
      }
      RowValue committed = copyForCommit(value, commitTs);
      committed.rowKey = key.getRowId();
      committedRowCache.put(key, committed);
    }
  }

  private static RowValue copyWithRowKey(RowValue source, long rowId) {
    RowValue copy = new RowValue();
    copy.txnId = source.txnId;
    copy.deleted = source.deleted;
    copy.payload = source.payload;
    copy.rowKey = rowId;
    copy.commitTs = source.commitTs;
    return copy;
  }

  /**
   * 主键点查单列快路径的可见值结果�?   *
   * <p>该对象只保存已经解码出的 H2 {@link Value} 和对�?committed version，用�?JDBC
   * 快路径构�?ResultSet 或复用上层列值缓存；它不持有底层 store value�?/p>
   */
  public static final class VisibleColumnValue {
    private final long commitTs;
    private final Value value;
    private final boolean latestCommitted;

    private VisibleColumnValue(long commitTs, Value value,
        boolean latestCommitted) {
      this.commitTs = commitTs;
      this.value = value;
      this.latestCommitted = latestCommitted;
    }

    public long commitTs() {
      return commitTs;
    }

    public Value value() {
      return value;
    }

    public boolean latestCommitted() {
      return latestCommitted;
    }
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

  private static void seekToTableScanRange(VersionScanSource scan,
      PrefixKey prefixKey, Long minRowId, Long maxRowId) {
    byte[] lowerInclusive = tableScanStartKey(prefixKey, minRowId);
    if (maxRowId == null || scan.direction() != ScanDirection.FORWARD) {
      scan.seekToRangeStart(lowerInclusive, tableScanEndKey(prefixKey,
          maxRowId));
      return;
    }
    scan.seekToRangeClosed(lowerInclusive,
        tableScanClosedEndKey(prefixKey, maxRowId));
  }

  private static byte[] tableScanClosedEndKey(PrefixKey prefixKey,
      long maxRowId) {
    byte[] rowPrefix = buildRowSeekKey(prefixKey, maxRowId);
    byte[] data = Arrays.copyOf(rowPrefix, RAW_VERSION_ROW_KEY_LENGTH);
    Arrays.fill(data, rowPrefix.length, data.length, (byte) 0xff);
    return data;
  }

  private static boolean resolveVisibleCountableInCurrentLogicalRow(
      VersionScanSource scan, byte[] rowPrefix, long startTs) {
    while (scan.isValid()) {
      byte[] rawKey = scan.key();
      if (rawKey == null || !TableScanCursor.startsWith(rawKey, rowPrefix)) {
        return false;
      }

      if (isRawVersionRowKey(rawKey)) {
        if (!isRawCommittedVersion(rawKey)) {
          scan.advance();
          continue;
        }

        long commitTs = rawCommitTs(rawKey);
        if (commitTs > startTs) {
          scan.advance();
          continue;
        }

        int countableState = RowValue.countableState(scan.value());
        if (countableState != RowValue.COUNTABLE_INVALID) {
          skipCurrentLogicalRow(scan, null, rowPrefix);
          return countableState == RowValue.COUNTABLE_ROW;
        }

        scan.advance();
        continue;
      }

      VersionKey versionKey = VersionKey.fromBytes(rawKey);
      if (!versionKey.isCommited()) {
        scan.advance();
        continue;
      }

      byte[] encoded = scan.value();
      if (encoded != null && encoded.length > 0
          && RowValue.commitTs(encoded) <= startTs) {
        int countableState = RowValue.countableState(encoded);
        if (countableState != RowValue.COUNTABLE_INVALID) {
          skipCurrentLogicalRow(scan, null, rowPrefix);
          return countableState == RowValue.COUNTABLE_ROW;
        }
      }

      scan.advance();
    }
    return false;
  }

  private static boolean resolveVisibleCountableInCurrentRawLogicalRow(
      VersionScanSource scan, Slice firstRowKey, long startTs) {
    long firstRowId = rawRowId(firstRowKey);
    Slice rawKey = firstRowKey;
    while (scan.isValid()) {
      if (!sameRawLogicalRow(rawKey, firstRowId)) {
        return false;
      }
      if (!isRawCommittedVersion(rawKey)) {
        scan.advance();
        rawKey = scan.isValid() ? scan.keyView() : null;
        continue;
      }

      long commitTs = rawCommitTs(rawKey);
      if (commitTs > startTs) {
        scan.advance();
        rawKey = scan.isValid() ? scan.keyView() : null;
        continue;
      }

      int countableState = RowValue.countableStateView(scan.valueView());
      if (countableState != RowValue.COUNTABLE_INVALID) {
        skipCurrentRawLogicalRow(scan, null, firstRowId);
        return countableState == RowValue.COUNTABLE_ROW;
      }

      scan.advance();
      rawKey = scan.isValid() ? scan.keyView() : null;
    }
    return false;
  }

  private static long countLocalRowsMissingFromStore(Transaction2 txn,
      byte[] tablePrefix, Long minRowId, Long maxRowId,
      Set<DataKey> rowsCoveredByStoreScan) {
    long count = 0L;
    for (Map.Entry<DataKey, RowValue> entry : txn.getWriteSet().entrySet()) {
      DataKey key = entry.getKey();
      if (key == null || !key.isRow()) {
        continue;
      }
      if (!TableScanCursor.startsWith(key.toBytes(), tablePrefix)) {
        continue;
      }
      if (!inRowIdRange(key.getRowId(), minRowId, maxRowId)) {
        continue;
      }
      if (rowsCoveredByStoreScan.contains(key)) {
        continue;
      }
      if (isCountable(entry.getValue())) {
        count++;
      }
    }
    return count;
  }

  private static long countLocalRowsMissingFromStore(
      Map<Long, RowValue> localRowWrites, Set<Long> rowsCoveredByStoreScan) {
    long count = 0L;
    for (Map.Entry<Long, RowValue> entry : localRowWrites.entrySet()) {
      if (rowsCoveredByStoreScan.contains(entry.getKey())) {
        continue;
      }
      if (isCountable(entry.getValue())) {
        count++;
      }
    }
    return count;
  }

  private static boolean isCountable(RowValue value) {
    return value != null
        && !value.deleted
        && value.payload != null
        && value.payload.length > 0;
  }

  private static boolean inRowIdRange(long rowId, Long minRowId,
      Long maxRowId) {
    if (minRowId != null && rowId < minRowId) {
      return false;
    }
    if (maxRowId != null && rowId > maxRowId) {
      return false;
    }
    return true;
  }

  private static void skipCurrentLogicalRow(VersionScanSource scan,
      byte[] tablePrefix, byte[] currentRowPrefix) {
    while (scan.isValid()) {
      scan.advance();
      if (!scan.isValid()) {
        return;
      }

      byte[] key = scan.key();
      if (tablePrefix != null
          && !TableScanCursor.startsWith(key, tablePrefix)) {
        return;
      }
      if (!TableScanCursor.startsWith(key, currentRowPrefix)) {
        return;
      }
    }
  }

  private static void skipCurrentRawLogicalRow(VersionScanSource scan,
      byte[] tablePrefix, long currentRowId) {
    while (scan.isValid()) {
      scan.advance();
      if (!scan.isValid()) {
        return;
      }

      Slice key = scan.keyView();
      if (tablePrefix != null && !startsWith(key, tablePrefix)) {
        return;
      }
      if (!sameRawLogicalRow(key, currentRowId)) {
        return;
      }
    }
  }

  private static boolean startsWith(Slice value, byte[] prefix) {
    if (value == null || prefix == null || prefix.length > value.length()) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if (value.getByte(i) != prefix[i]) {
        return false;
      }
    }
    return true;
  }

  private static boolean isRawVersionRowKey(byte[] rawKey) {
    return rawKey != null && rawKey.length == RAW_VERSION_ROW_KEY_LENGTH;
  }

  private static boolean isRawVersionRowKey(Slice rawKey) {
    return rawKey != null && rawKey.length() == RAW_VERSION_ROW_KEY_LENGTH;
  }

  private static boolean isRawCommittedVersion(byte[] rawKey) {
    return isRawVersionRowKey(rawKey)
        && rawKey[RAW_COMMITTED_OFFSET] == (byte) 1;
  }

  private static boolean isRawCommittedVersion(Slice rawKey) {
    return isRawVersionRowKey(rawKey)
        && rawKey.getByte(RAW_COMMITTED_OFFSET) == (byte) 1;
  }

  private static boolean sameRawLogicalRow(byte[] rawKey,
      byte[] firstRowKey) {
    if (!isRawVersionRowKey(rawKey) || !isRawVersionRowKey(firstRowKey)) {
      return false;
    }
    for (int i = 0; i < RAW_ROW_KEY_PREFIX_LENGTH; i++) {
      if (rawKey[i] != firstRowKey[i]) {
        return false;
      }
    }
    return true;
  }

  private static boolean sameRawLogicalRow(Slice rawKey, long firstRowId) {
    return isRawVersionRowKey(rawKey) && rawRowId(rawKey) == firstRowId;
  }

  /**
   * 直接�?VersionRowKey 的磁盘编码中解析 rowId�?   *
   * <p>该方法只服务于无本地写事务的 range count 快路径，依赖
   * VersionRowKey 当前的固定布局：table header(13) + rowId(8) +
   * committed(1) + version(8)。如果未�?key 编码变更，必须同步更新这�?   * RAW_* offset 常量，或让快路径回退到对象化解析�?/p>
   */
  private static long rawRowId(byte[] rawKey) {
    return Key.flipSign(readLong(rawKey, RAW_ROW_ID_OFFSET));
  }

  private static long rawRowId(Slice rawKey) {
    return Key.flipSign(readLong(rawKey, RAW_ROW_ID_OFFSET));
  }

  /**
   * 直接�?committed VersionRowKey 的磁盘编码中还原提交时间戳�?   *
   * <p>row version key 为了让新版本排在旧版本前，落盘时写入
   * {@code flipSign(Long.MAX_VALUE - commitTs)}。点查可见性扫描可以先�?key
   * 判断该版本是否晚于当前快照，只有可能可见时才解码 value，避免跳过新版本时复�?payload�?/p>
   */
  private static long rawCommitTs(byte[] rawKey) {
    return Long.MAX_VALUE - Key.flipSign(readLong(rawKey,
        RAW_VERSION_OFFSET));
  }

  private static long rawCommitTs(Slice rawKey) {
    return Long.MAX_VALUE - Key.flipSign(readLong(rawKey,
        RAW_VERSION_OFFSET));
  }

  private static long readLong(byte[] data, int offset) {
    return ((long) (data[offset] & 0xff) << 56)
        | ((long) (data[offset + 1] & 0xff) << 48)
        | ((long) (data[offset + 2] & 0xff) << 40)
        | ((long) (data[offset + 3] & 0xff) << 32)
        | ((long) (data[offset + 4] & 0xff) << 24)
        | ((long) (data[offset + 5] & 0xff) << 16)
        | ((long) (data[offset + 6] & 0xff) << 8)
        | (long) (data[offset + 7] & 0xff);
  }

  private static long readLong(Slice data, int offset) {
    return ((long) (data.getByte(offset) & 0xff) << 56)
        | ((long) (data.getByte(offset + 1) & 0xff) << 48)
        | ((long) (data.getByte(offset + 2) & 0xff) << 40)
        | ((long) (data.getByte(offset + 3) & 0xff) << 32)
        | ((long) (data.getByte(offset + 4) & 0xff) << 24)
        | ((long) (data.getByte(offset + 5) & 0xff) << 16)
        | ((long) (data.getByte(offset + 6) & 0xff) << 8)
        | (long) (data.getByte(offset + 7) & 0xff);
  }

  static byte[] buildRowSeekKey(PrefixKey prefixKey, long rowId) {
    byte[] prefix = prefixKey.toBytes();
    byte[] data = Arrays.copyOf(prefix, prefix.length + Long.BYTES);
    // VersionRowKey / RowKey �?rowId 做符号位翻转以保�?signed long 的字典序�?    // range seek bound 必须使用相同编码，否则正数主键范围会从表前部开始扫描�?    putLong(data, prefix.length, Key.flipSign(rowId));
    return data;
  }

  private static void putLong(byte[] data, int offset, long value) {
    data[offset] = (byte) (value >>> 56);
    data[offset + 1] = (byte) (value >>> 48);
    data[offset + 2] = (byte) (value >>> 40);
    data[offset + 3] = (byte) (value >>> 32);
    data[offset + 4] = (byte) (value >>> 24);
    data[offset + 5] = (byte) (value >>> 16);
    data[offset + 6] = (byte) (value >>> 8);
    data[offset + 7] = (byte) value;
  }

  static boolean rangeCountSegmentsEnabled() {
    return Boolean.getBoolean(RANGE_COUNT_SEGMENT_ENABLED_PROPERTY);
  }

  static long segmentIdForRowId(long rowId) {
    return Math.floorDiv(rowId, RANGE_COUNT_SEGMENT_SIZE);
  }

  private static int rangeCountSegmentMinSegments() {
    return Math.max(1, Integer.getInteger(
        RANGE_COUNT_SEGMENT_MIN_SEGMENTS_PROPERTY,
        DEFAULT_RANGE_COUNT_SEGMENT_MIN_SEGMENTS));
  }

  private static SegmentSpan fullyCoveredSegments(long minRowId,
      long maxRowId) {
    long firstSegmentId = segmentIdForRowId(minRowId);
    long firstSegmentStart = segmentStart(firstSegmentId);
    if (firstSegmentStart < minRowId) {
      firstSegmentId++;
      firstSegmentStart = segmentStart(firstSegmentId);
    }

    long lastSegmentId = segmentIdForRowId(maxRowId);
    long lastSegmentEnd = segmentEnd(lastSegmentId);
    if (lastSegmentEnd > maxRowId) {
      lastSegmentId--;
      lastSegmentEnd = segmentEnd(lastSegmentId);
    }

    if (firstSegmentId > lastSegmentId) {
      return null;
    }
    long fullSegmentCount = lastSegmentId - firstSegmentId + 1L;
    return new SegmentSpan(firstSegmentId, lastSegmentId,
        firstSegmentStart, lastSegmentEnd, fullSegmentCount);
  }

  private static long segmentStart(long segmentId) {
    return segmentId * RANGE_COUNT_SEGMENT_SIZE;
  }

  private static long segmentEnd(long segmentId) {
    return segmentStart(segmentId) + RANGE_COUNT_SEGMENT_SIZE - 1L;
  }

  private static int compareUnsigned(byte[] left, byte[] right) {
    int length = Math.min(left.length, right.length);
    for (int i = 0; i < length; i++) {
      int diff = (left[i] & 0xff) - (right[i] & 0xff);
      if (diff != 0) {
        return diff;
      }
    }
    return left.length - right.length;
  }

  private static final class SegmentSpan {
    private final long firstSegmentId;
    private final long lastSegmentId;
    private final long firstSegmentStart;
    private final long lastSegmentEnd;
    private final long fullSegmentCount;

    private SegmentSpan(long firstSegmentId, long lastSegmentId,
        long firstSegmentStart, long lastSegmentEnd,
        long fullSegmentCount) {
      this.firstSegmentId = firstSegmentId;
      this.lastSegmentId = lastSegmentId;
      this.firstSegmentStart = firstSegmentStart;
      this.lastSegmentEnd = lastSegmentEnd;
      this.fullSegmentCount = fullSegmentCount;
    }
  }

  private static final class SegmentRowCountBase {
    private static final SegmentRowCountBase EMPTY =
        new SegmentRowCountBase(0L, 0L);
    private final long rowCount;
    private final long commitTs;

    private SegmentRowCountBase(long rowCount, long commitTs) {
      this.rowCount = rowCount;
      this.commitTs = commitTs;
    }
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

  /**
   * 释放内部 helper 创建的事务快照�?   *
   * <p>该方法只从活跃事务集合移除事务，不调用底�?store rollback。Online DDL
   * backfill 这类内部流程会用事务对象读取一致快照，并通过专用批量接口写入已经
   * committed 的索引项；如果再调用 rollback，未来一旦批量接口改为记�?txn ref�?   * 就可能误删已经回填的索引项�?/p>
   *
   * @param txn 内部 helper 创建的事�?   */
  void releaseInternalTransaction(Transaction2 txn) {
    if (txn != null) {
      activeTransactions.remove(txn.getTxnId());
    }
  }



}
