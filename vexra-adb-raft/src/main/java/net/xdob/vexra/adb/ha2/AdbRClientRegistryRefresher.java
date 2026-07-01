package net.xdob.vexra.adb.ha2;

import net.xdob.vexra.adb.db.AdbControlPlaneSnapshot;
import net.xdob.vexra.cluster.region.RegionMetadata;

import java.sql.SQLException;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 基于控制面快照刷新 ADB RClient registry。
 *
 * <p>刷新器读取当前 region leader，并通过部署层提供的
 * {@link AdbRClientFactory} 补齐缺失的 {@link RClient} 映射。该对象只管理
 * 自己注册过的 replicaId：leader 消失时只移除托管映射，不关闭 client，也不
 * 删除部署层手工注册的连接。</p>
 */
public final class AdbRClientRegistryRefresher {
  private final AdbRClientRegistry registry;
  private final AdbRClientFactory factory;
  private final Set<String> managedReplicaIds = new LinkedHashSet<>();

  /**
   * 创建 RClient registry 刷新器。
   *
   * @param registry 待刷新的 RClient registry
   * @param factory 部署层 RClient 工厂
   */
  public AdbRClientRegistryRefresher(AdbRClientRegistry registry,
      AdbRClientFactory factory) {
    this.registry = Objects.requireNonNull(registry, "registry == null");
    this.factory = Objects.requireNonNull(factory, "factory == null");
  }

  /**
   * 按控制面快照刷新 registry。
   *
   * @param snapshot 当前控制面 region 路由快照
   * @return 本次刷新结果
   * @throws SQLException 创建 RClient 失败时抛出
   */
  public synchronized AdbRClientRegistryRefreshResult refresh(
      AdbControlPlaneSnapshot snapshot) throws SQLException {
    Objects.requireNonNull(snapshot, "snapshot == null");
    LeaderScan scan = scanLeaders(snapshot);
    int registered = 0;
    int retained = 0;
    for (String leaderId : scan.activeLeaderIds) {
      if (registry.get(leaderId).isPresent()) {
        retained++;
        continue;
      }
      RClient client = Objects.requireNonNull(factory.create(leaderId),
          "factory returned null client");
      registry.register(leaderId, client);
      managedReplicaIds.add(leaderId);
      registered++;
    }

    int unregistered = unregisterStaleManagedClients(scan.activeLeaderIds);
    return new AdbRClientRegistryRefreshResult(scan.activeLeaderIds,
        registered, retained, unregistered, scan.regionsWithoutLeader);
  }

  private LeaderScan scanLeaders(AdbControlPlaneSnapshot snapshot) {
    Set<String> activeLeaderIds = new LinkedHashSet<>();
    int regionsWithoutLeader = 0;
    for (RegionMetadata region : snapshot.getRegions()) {
      String leaderId = region.getReplicaMetadata().getLeaderId();
      if (leaderId == null || leaderId.trim().isEmpty()) {
        regionsWithoutLeader++;
        continue;
      }
      activeLeaderIds.add(leaderId.trim());
    }
    return new LeaderScan(activeLeaderIds, regionsWithoutLeader);
  }

  private int unregisterStaleManagedClients(Set<String> activeLeaderIds) {
    int unregistered = 0;
    Iterator<String> iterator = managedReplicaIds.iterator();
    while (iterator.hasNext()) {
      String managedReplicaId = iterator.next();
      if (!activeLeaderIds.contains(managedReplicaId)) {
        registry.unregister(managedReplicaId);
        iterator.remove();
        unregistered++;
      }
    }
    return unregistered;
  }

  private static final class LeaderScan {
    private final Set<String> activeLeaderIds;
    private final int regionsWithoutLeader;

    private LeaderScan(Set<String> activeLeaderIds, int regionsWithoutLeader) {
      this.activeLeaderIds = activeLeaderIds;
      this.regionsWithoutLeader = regionsWithoutLeader;
    }
  }
}
