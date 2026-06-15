package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.ha2.AdbRaftRegionCommitTransport;
import net.xdob.vexra.adb.ha2.RaftRClient;
import net.xdob.vexra.adb.key.DataKey;
import net.xdob.vexra.adb.key.IndexKey;
import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.RowPrefix;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.cluster.region.KeyRange;
import net.xdob.vexra.cluster.region.RegionMetadata;
import net.xdob.vexra.cluster.region.RegionRouter;
import net.xdob.vexra.ha.ReplicaRole;
import net.xdob.vexra.ha.VirtualNodeMetadata;
import net.xdob.vexra.ha.VirtualNodeReplica;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * ADB SQL 分布式写入 runtime。
 *
 * <p>该 runtime 只在 table-engine 参数显式开启远端写入时安装。它把 SQL 表写入产生的
 * ADB logical key 映射到远端 region table id/epoch，再复用现有
 * {@link AdbRegionCommitCoordinator} 和 Raft commit transport 写入 region node。
 * 默认不开启时，旧的本地 ADB/H2 写路径保持不变。</p>
 */
public final class AdbSqlDistributedWriteRuntime implements AutoCloseable {
  private final AdbSqlDistributedScanConfig config;
  private final RaftRClient rClient;
  private final AdbRpcRegionCommitClient commitClient;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  /**
   * 创建 SQL 分布式写入 runtime。
   *
   * @param config table-engine 分布式读写配置
   */
  public AdbSqlDistributedWriteRuntime(AdbSqlDistributedScanConfig config) {
    this.config = Objects.requireNonNull(config, "config == null");
    if (!config.isRaftWriteClient()) {
      throw new IllegalArgumentException("raft write client is not enabled");
    }
    Properties properties = new Properties();
    properties.setProperty("HA2.GROUP", config.getRaftGroup());
    properties.setProperty("HA2.NODES", config.getRaftPeers());
    this.rClient = new RaftRClient(properties);
    this.commitClient = new AdbRpcRegionCommitClient(
        new AdbRaftRegionCommitTransport(config.getRaftDbName(), rClient),
        config.getWriteTimeoutMillis());
  }

  /**
   * 为指定 SQL 表构造 region commit coordinator。
   *
   * @param localTabId SQL 本地表标识
   * @return 已带远端 key 映射的 commit coordinator
   */
  public AdbRegionCommitCoordinator coordinator(TabId localTabId) {
    TabId remoteTabId = remoteTabId(localTabId);
    return new AdbRegionCommitCoordinator(router(remoteTabId), commitClient,
        keyMapper(localTabId, remoteTabId), true);
  }

  /**
   * 关闭远端写入 runtime 持有的 Raft 连接资源。
   *
   * @throws Exception 关闭底层连接失败时抛出
   */
  @Override
  public void close() throws Exception {
    if (closed.compareAndSet(false, true)) {
      try {
        commitClient.close();
      } finally {
        rClient.close();
      }
    }
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
    if (splitRowId == null) {
      regions.add(region("r1", new KeyRange(tableStart,
          normalizeEnd(tableEnd)), "sql-node-a", 1));
    } else {
      byte[] splitKey = RowKey.of(tabId, splitRowId).toBytes();
      regions.add(region("r1", new KeyRange(tableStart, splitKey),
          "sql-node-a", 1));
      regions.add(region("r2", new KeyRange(splitKey,
          normalizeEnd(tableEnd)), "sql-node-b", 1));
    }
    return new RegionRouter(regions);
  }

  private RegionMetadata region(String regionId, KeyRange range,
      String leaderId, long epoch) {
    return new RegionMetadata(regionId, range, epoch,
        new VirtualNodeMetadata("vn-" + regionId, epoch, leaderId,
            Arrays.asList(
                new VirtualNodeReplica("sql-node-a", ReplicaRole.DATA_VOTER),
                new VirtualNodeReplica("sql-node-b", ReplicaRole.DATA_VOTER),
                new VirtualNodeReplica("sql-witness",
                    ReplicaRole.WITNESS_VOTER)),
            0, 0, 0));
  }

  private static Function<DataKey, DataKey> keyMapper(TabId localTabId,
      TabId remoteTabId) {
    if (localTabId.equals(remoteTabId)) {
      return Function.identity();
    }
    return key -> {
      if (key.getTableId() != localTabId.id
          || key.getEpoch() != localTabId.epoch) {
        throw new IllegalArgumentException("unexpected SQL write key table: "
            + key + ", localTabId=" + localTabId);
      }
      if (key.isRow()) {
        return RowKey.of(remoteTabId, key.getRowId());
      }
      IndexKey indexKey = (IndexKey) key;
      return IndexKey.of(remoteTabId, indexKey.getIndexId(),
          indexKey.getIndex(), indexKey.getRowId());
    };
  }

  private static byte[] normalizeEnd(byte[] endKey) {
    return endKey == null ? new byte[0] : endKey;
  }
}
