package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.RowPrefix;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.cluster.region.KeyRange;
import net.xdob.vexra.cluster.region.RegionMetadata;
import net.xdob.vexra.cluster.region.RegionRouter;
import net.xdob.vexra.cluster.sql.DistributedPlan;
import net.xdob.vexra.ha.VirtualNodeMetadata;
import org.h2.index.Cursor;
import org.h2.message.DbException;
import org.h2.result.Row;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ADB SQL 分布式 scan runtime。
 *
 * <p>该 runtime 位于 H2 `Index.find(...)` 与 ADB region executor 之间：把 H2
 * 传入的主键范围转换为 `DistributedPlan`，通过可替换的 region scan executor 执行，
 * 再把 region 返回的 ADB payload 还原为 H2 `Row`。它只在表显式开启时生效，
 * 不改变默认本地 scan 行为。</p>
 */
public final class AdbSqlDistributedScanRuntime implements AutoCloseable {
  /**
   * region scan 结果中保存原始 ADB row payload 的字段名。
   */
  public static final String PAYLOAD_BYTES_FIELD = "payload_bytes";

  private final DbStore store;
  private final AdbSqlDistributedScanConfig config;
  private final AdbDistributedRegionScanExecutor executor;
  private final AutoCloseable closeable;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  /**
   * 创建 SQL 分布式 scan runtime。
   *
   * @param store ADB store
   * @param config 分布式 scan 配置
   */
  public AdbSqlDistributedScanRuntime(DbStore store,
      AdbSqlDistributedScanConfig config) {
    this(store, config, scanTarget(store, config));
  }

  /**
   * 创建 SQL 分布式 scan runtime。
   *
   * @param store ADB store
   * @param config 分布式 scan 配置
   * @param executor region scan executor
   */
  public AdbSqlDistributedScanRuntime(DbStore store,
      AdbSqlDistributedScanConfig config,
      AdbDistributedRegionScanExecutor executor) {
    this.store = Objects.requireNonNull(store, "store == null");
    this.config = Objects.requireNonNull(config, "config == null");
    this.executor = Objects.requireNonNull(executor, "executor == null");
    this.closeable = () -> {
    };
  }

  private AdbSqlDistributedScanRuntime(DbStore store,
      AdbSqlDistributedScanConfig config, AdbSqlDistributedScanTarget target) {
    this.store = Objects.requireNonNull(store, "store == null");
    this.config = Objects.requireNonNull(config, "config == null");
    this.executor = Objects.requireNonNull(target, "target == null")
        .executor();
    this.closeable = target.closeable();
  }

  /**
   * 判断 SQL 分布式 scan 是否启用。
   *
   * @return 启用返回 true
   */
  public boolean isEnabled() {
    return config.isEnabled();
  }

  /**
   * 执行 SQL 主键范围 scan 并返回 H2 cursor。
   *
   * @param txn 当前 H2 session 对应的 ADB 事务
   * @param tabId 当前表版本标识
   * @param minRowId 起始 rowId；null 表示表起始
   * @param maxRowId 结束 rowId；null 表示表结束
   * @return H2 cursor
   * @throws SQLException 当 distributed scan 失败时抛出
   */
  public Cursor findRows(Transaction2 txn, TabId tabId, Long minRowId,
      Long maxRowId) throws SQLException {
    DistributedPlan plan = buildPlan(tabId, minRowId, maxRowId,
        txn.getStartTs(), false);
    List<Map<String, Object>> rows = executor.executeRows(txn, plan,
        config.getTimeoutMillis());
    return new DistributedSqlRowCursor(toRows(rows));
  }

  /**
   * 输出当前表 scan 的诊断计划。
   *
   * @param tabId 当前表版本标识
   * @param minRowId 起始 rowId；null 表示表起始
   * @param maxRowId 结束 rowId；null 表示表结束
   * @param readTimestamp 读时间戳
   * @return explain 文本行
   */
  public List<String> explain(TabId tabId, Long minRowId, Long maxRowId,
      long readTimestamp) {
    DistributedPlan plan = buildPlan(tabId, minRowId, maxRowId, readTimestamp,
        false);
    return adapter(tabId).explain(plan);
  }

  /**
   * 返回可嵌入 H2 `EXPLAIN SELECT` 的简短 plan 标记。
   *
   * @return plan SQL 标记
   */
  public String getPlanMarker() {
    return "ADB_DISTRIBUTED_SCAN regions=" + configuredRegionCount()
        + " splitRow=" + config.getSplitRowId()
        + " client=" + config.getScanClient()
        + " tableId=" + config.getTableId()
        + " tableEpoch=" + config.getTableEpoch()
        + " readTs=" + config.getReadTimestamp()
        + " timeoutMillis=" + config.getTimeoutMillis();
  }

  /**
   * 关闭 SQL distributed scan runtime 持有的远端连接资源。
   *
   * <p>本地 scan 没有实际资源；远端 scan 会关闭扩展模块持有的连接资源。该方法幂等，便于
   * H2 table close/remove 多次调用。</p>
   *
   * @throws Exception 关闭底层远端 client 失败时抛出
   */
  @Override
  public void close() throws Exception {
    if (closed.compareAndSet(false, true)) {
      closeable.close();
    }
  }

  private int configuredRegionCount() {
    return config.getSplitRowId() == null ? 1 : 2;
  }

  private DistributedPlan buildPlan(TabId tabId, Long minRowId, Long maxRowId,
      long readTimestamp, boolean countOnly) {
    TabId remoteTabId = remoteTabId(tabId);
    return adapter(remoteTabId).tableRowScan(remoteTabId, minRowId, maxRowId,
        Collections.emptyList(), Collections.emptyList(), 0,
        effectiveReadTimestamp(readTimestamp), countOnly);
  }

  private long effectiveReadTimestamp(long transactionReadTimestamp) {
    Long configured = config.getReadTimestamp();
    return configured == null ? transactionReadTimestamp : configured;
  }

  private AdbDistributedPlanAdapter adapter(TabId tabId) {
    return new AdbDistributedPlanAdapter(router(tabId));
  }

  private TabId remoteTabId(TabId localTabId) {
    Integer tableId = config.getTableId();
    Long tableEpoch = config.getTableEpoch();
    if (tableId == null && tableEpoch == null) {
      return localTabId;
    }
    return TabId.of(tableId == null ? localTabId.id : tableId,
        tableEpoch == null ? localTabId.epoch : tableEpoch);
  }

  private RegionRouter router(TabId tabId) {
    byte[] tableStart = RowPrefix.of(tabId).toBytes();
    byte[] tableEnd = KeyCodec.prefixEnd(tableStart);
    List<RegionMetadata> regions = new ArrayList<>();
    Long splitRowId = config.getSplitRowId();
    AdbSqlRegionReplicaSet replicas = config.isRaftScanClient()
        ? AdbSqlRegionReplicaSet.parse(config.getRaftPeers())
        : AdbSqlRegionReplicaSet.localDefault();
    if (splitRowId == null) {
      regions.add(region("r1", new KeyRange(tableStart,
          normalizeEnd(tableEnd)), replicas.leaderId(0), 1, replicas));
    } else {
      byte[] splitKey = RowKey.of(tabId, splitRowId).toBytes();
      regions.add(region("r1", new KeyRange(tableStart, splitKey),
          replicas.leaderId(0), 1, replicas));
      regions.add(region("r2", new KeyRange(splitKey,
          normalizeEnd(tableEnd)), replicas.leaderId(1), 1, replicas));
    }
    return new RegionRouter(regions);
  }

  private RegionMetadata region(String regionId, KeyRange range,
      String leaderId, long epoch, AdbSqlRegionReplicaSet replicas) {
    return new RegionMetadata(regionId, range, epoch,
        new VirtualNodeMetadata("vn-" + regionId, epoch, leaderId,
            replicas.replicas(), 0, 0, 0));
  }

  private static AdbSqlDistributedScanTarget scanTarget(DbStore store,
      AdbSqlDistributedScanConfig config) {
    Objects.requireNonNull(store, "store == null");
    Objects.requireNonNull(config, "config == null");
    if (!config.isRaftScanClient()) {
      return new AdbSqlDistributedScanTarget(new AdbDistributedRegionScanExecutor(
          new AdbLocalRegionScanClient(new AdbLocalRegionScanExecutor(store))),
          () -> {
          });
    }
    try {
      return extension(config.getScanClient()).createScanTarget(store, config);
    } catch (SQLException e) {
      throw DbException.convert(e);
    }
  }

  private static AdbSqlDistributedRuntimeExtension extension(String client) {
    ServiceLoader<AdbSqlDistributedRuntimeExtension> loader =
        ServiceLoader.load(AdbSqlDistributedRuntimeExtension.class);
    for (AdbSqlDistributedRuntimeExtension extension : loader) {
      if (extension.supportsScanClient(client)) {
        return extension;
      }
    }
    throw DbException.getUnsupportedException(
        "Distributed SQL scan client '" + client
            + "' requires an extension module such as vexra-adb-raft");
  }

  private static byte[] normalizeEnd(byte[] endKey) {
    return endKey == null ? new byte[0] : endKey;
  }

  private List<Row> toRows(List<Map<String, Object>> rawRows)
      throws SQLException {
    List<Row> rows = new ArrayList<>();
    for (Map<String, Object> rawRow : rawRows) {
      Object rowIdValue = rawRow.get("row_id");
      Object payload = rawRow.get(PAYLOAD_BYTES_FIELD);
      if (!(rowIdValue instanceof Number) || !(payload instanceof byte[])) {
        throw new SQLException("Distributed SQL row is missing row_id or "
            + PAYLOAD_BYTES_FIELD);
      }
      rows.add(RowCodec.decode(((Number) rowIdValue).longValue(),
          (byte[]) payload));
    }
    return rows;
  }

  /**
   * 基于内存 row 列表的 H2 cursor。
   */
  private static final class DistributedSqlRowCursor implements Cursor {
    private final List<Row> rows;
    private int index = -1;

    private DistributedSqlRowCursor(List<Row> rows) {
      this.rows = rows;
    }

    @Override
    public Row get() {
      return index >= 0 && index < rows.size() ? rows.get(index) : null;
    }

    @Override
    public Row getSearchRow() {
      return get();
    }

    @Override
    public boolean next() {
      if (index + 1 >= rows.size()) {
        return false;
      }
      index++;
      return true;
    }

    @Override
    public boolean previous() {
      throw org.h2.message.DbException.getUnsupportedException("previous");
    }
  }

}
