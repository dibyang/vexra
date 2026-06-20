package net.xdob.vexra.adb.db;

import net.xdob.vexra.cluster.region.RegionMetadata;

import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ADB 轻量控制面服务入口。
 *
 * <p>该类是 GA-03 的进程内服务 façade：它把持久化控制面 store、节点心跳状态机、
 * route 发布、TSO 分配和 system table 输出聚合到一个边界。当前实现不打开网络端口，
 * 也不改变控制面磁盘格式；后续 RPC/多副本控制面可以保留该方法语义并替换调用边界。</p>
 */
public final class AdbControlPlaneServer implements AdbRouteSnapshotPublisher {
  private final AdbPersistentControlPlaneStore store;
  private final AdbNodeHeartbeatService heartbeatService;
  private final AdbSystemTableProvider systemTables;

  /**
   * 创建轻量控制面服务入口。
   *
   * @param store 持久化控制面 store
   * @param suspectAfterMillis 节点心跳超过该时长后进入 SUSPECT
   * @param downAfterMillis 节点心跳超过该时长后进入 DOWN
   */
  public AdbControlPlaneServer(AdbPersistentControlPlaneStore store,
      long suspectAfterMillis, long downAfterMillis) {
    this.store = Objects.requireNonNull(store, "store == null");
    this.heartbeatService = new AdbNodeHeartbeatService(store,
        suspectAfterMillis, downAfterMillis);
    this.systemTables = new AdbSystemTableProvider(store);
  }

  /**
   * 接收节点心跳并持久化节点为 UP 状态。
   *
   * @param heartbeat 节点心跳
   * @throws SQLException 控制面持久化失败时抛出
   */
  public void heartbeat(AdbNodeHeartbeat heartbeat) throws SQLException {
    heartbeatService.heartbeat(heartbeat);
  }

  /**
   * 按当前时间推进心跳超时状态机。
   *
   * @param nowMillis 当前时间戳
   * @return 本次被更新状态的节点数量
   * @throws SQLException 控制面读取或写入失败时抛出
   */
  public int evaluateHeartbeatTimeouts(long nowMillis) throws SQLException {
    return heartbeatService.evaluateTimeouts(nowMillis);
  }

  /**
   * 发布新的 region 路由快照并推进 route epoch。
   *
   * @param newRegions 新 region 元数据集合
   * @return 发布后的 route epoch
   */
  @Override
  public long publishRegions(Collection<RegionMetadata> newRegions) {
    return store.publishRegions(newRegions);
  }

  /**
   * 读取当前 route 快照。
   *
   * @return 当前控制面 route 快照
   */
  @Override
  public AdbControlPlaneSnapshot getSnapshot() {
    return store.getSnapshot();
  }

  /**
   * 分配全局单调时间戳。
   *
   * @return 新分配的时间戳
   */
  @Override
  public long nextTimestamp() {
    return store.nextTimestamp();
  }

  /**
   * 观察 route epoch 是否已经变化。
   *
   * @param lastSeenEpoch 调用方已看到的 route epoch
   * @return route watch 观察结果
   */
  @Override
  public AdbRouteWatch watchRoutes(long lastSeenEpoch) {
    return store.watchRoutes(lastSeenEpoch);
  }

  /**
   * 输出 ADB_NODES system table 行。
   *
   * @return 节点 system table 行
   * @throws SQLException 控制面读取失败时抛出
   */
  public List<Map<String, String>> nodes() throws SQLException {
    return systemTables.nodes();
  }

  /**
   * 输出 ADB_REGIONS system table 行。
   *
   * @return region system table 行
   * @throws SQLException 控制面读取失败时抛出
   */
  public List<Map<String, String>> regions() throws SQLException {
    return systemTables.regions();
  }

  /**
   * 输出 ADB_TSO system table 行。
   *
   * @return TSO system table 行
   * @throws SQLException 控制面读取失败时抛出
   */
  public List<Map<String, String>> tso() throws SQLException {
    return systemTables.tso();
  }

  /**
   * 输出 ADB_LEASES system table 行。
   *
   * @param nowMillis 当前时间戳，用于计算 active 字段
   * @return lease system table 行
   * @throws SQLException 控制面读取失败时抛出
   */
  public List<Map<String, String>> leases(long nowMillis)
      throws SQLException {
    return systemTables.leases(nowMillis);
  }

  /**
   * 输出 ADB_CONFIGS system table 行。
   *
   * @return config system table 行
   * @throws SQLException 控制面读取失败时抛出
   */
  public List<Map<String, String>> configs() throws SQLException {
    return systemTables.configs();
  }

  /**
   * 输出 ADB_CAPABILITIES system table 行。
   *
   * @param guard 生产范围 guard
   * @return capability system table 行
   */
  public List<Map<String, String>> capabilities(AdbProductionGuard guard) {
    return systemTables.capabilities(guard);
  }
}
