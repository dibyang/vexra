package net.xdob.vexra.adb.db;

import net.xdob.vexra.cluster.region.RegionMetadata;
import net.xdob.vexra.ha.VirtualNodeReplica;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ADB 控制面 system table 行提供器。
 *
 * <p>该类把 GA-03 持久化控制面中的 nodes、regions 和 TSO 转换为稳定的
 * system table 风格行。它不直接注册 H2 系统表，也不修改控制面状态；后续 SQL
 * 集成层可以复用这些行作为 `ADB_NODES`、`ADB_REGIONS`、`ADB_TSO`、`ADB_LEASES`
 * 和 `ADB_CAPABILITIES` 的数据源。</p>
 */
public final class AdbSystemTableProvider {
  private final AdbPersistentControlPlaneStore store;

  /**
   * 创建控制面 system table 行提供器。
   *
   * @param store 持久化控制面 store
   */
  public AdbSystemTableProvider(AdbPersistentControlPlaneStore store) {
    this.store = Objects.requireNonNull(store, "store == null");
  }

  /**
   * 输出 `ADB_NODES` 行。
   *
   * @return 节点 system table 行列表
   * @throws SQLException 读取控制面 store 失败时抛出
   */
  public List<Map<String, String>> nodes() throws SQLException {
    List<Map<String, String>> rows = new ArrayList<>();
    for (AdbControlPlaneNodeRecord node : store.listNodes()) {
      Map<String, String> row = new LinkedHashMap<>();
      row.put("node_id", node.getNodeId());
      row.put("role", node.getRole().name());
      row.put("host", node.getHost());
      row.put("port", Integer.toString(node.getPort()));
      row.put("status", node.getStatus().name());
      row.put("last_heartbeat_millis",
          Long.toString(node.getLastHeartbeatMillis()));
      row.put("commit_index", Long.toString(node.getCommitIndex()));
      row.put("applied_index", Long.toString(node.getAppliedIndex()));
      row.put("failure_domain", node.getFailureDomain());
      rows.add(row);
    }
    return rows;
  }

  /**
   * 输出 `ADB_REGIONS` 行。
   *
   * @return region system table 行列表
   * @throws SQLException 读取控制面 store 失败时抛出
   */
  public List<Map<String, String>> regions() throws SQLException {
    List<Map<String, String>> rows = new ArrayList<>();
    long routeEpoch = store.getRouteEpoch();
    for (RegionMetadata region : store.listRegions()) {
      Map<String, String> row = new LinkedHashMap<>(
          region.toSystemTableRow());
      row.put("route_epoch", Long.toString(routeEpoch));
      row.put("replicas", replicas(region));
      row.put("state", "ACTIVE");
      rows.add(row);
    }
    return rows;
  }

  /**
   * 输出 `ADB_TSO` 行。
   *
   * @return 单行 TSO system table 结果
   * @throws SQLException 读取控制面 store 失败时抛出
   */
  public List<Map<String, String>> tso() throws SQLException {
    Map<String, String> row = new LinkedHashMap<>();
    Optional<Long> lastIssued = store.getLastIssuedTimestamp();
    row.put("scope", "global");
    row.put("last_issued_ts", lastIssued.isPresent()
        ? Long.toString(lastIssued.get()) : "");
    row.put("initialized", Boolean.toString(lastIssued.isPresent()));
    row.put("route_epoch", Long.toString(store.getRouteEpoch()));
    List<Map<String, String>> rows = new ArrayList<>();
    rows.add(row);
    return rows;
  }

  /**
   * 输出 `ADB_LEASES` 行。
   *
   * @param nowMillis 当前时间戳，用于计算 active 字段
   * @return lease system table 行列表
   * @throws SQLException 读取控制面 store 失败时抛出
   */
  public List<Map<String, String>> leases(long nowMillis)
      throws SQLException {
    List<Map<String, String>> rows = new ArrayList<>();
    for (AdbControlPlaneLeaseRecord lease : store.listLeases()) {
      Map<String, String> row = new LinkedHashMap<>();
      row.put("lease_name", lease.getLeaseName());
      row.put("owner", lease.getOwner());
      row.put("epoch", Long.toString(lease.getEpoch()));
      row.put("expire_at_millis", Long.toString(lease.getExpireAtMillis()));
      row.put("fencing_token", Long.toString(lease.getFencingToken()));
      row.put("active", Boolean.toString(lease.isActive(nowMillis)));
      rows.add(row);
    }
    return rows;
  }

  /**
   * 输出 `ADB_CONFIGS` 行。
   *
   * @return 控制面配置 system table 行列表
   * @throws SQLException 读取控制面 store 失败时抛出
   */
  public List<Map<String, String>> configs() throws SQLException {
    List<Map<String, String>> rows = new ArrayList<>();
    for (AdbControlPlaneConfigRecord config : store.listConfigs()) {
      Map<String, String> row = new LinkedHashMap<>();
      row.put("config_key", config.getConfigKey());
      row.put("value", config.getValue());
      row.put("version", Long.toString(config.getVersion()));
      row.put("updated_at_millis",
          Long.toString(config.getUpdatedAtMillis()));
      rows.add(row);
    }
    return rows;
  }

  /**
   * 输出 `ADB_CAPABILITIES` 行。
   *
   * @param guard 生产范围 guard
   * @return capability system table 行列表
   */
  public List<Map<String, String>> capabilities(AdbProductionGuard guard) {
    Objects.requireNonNull(guard, "guard == null");
    List<Map<String, String>> rows = new ArrayList<>();
    for (AdbProductionCapability capability : AdbProductionCapability.values()) {
      Map<String, String> row = new LinkedHashMap<>();
      row.put("capability", capability.name());
      boolean enabled = isEnabled(guard, capability);
      row.put("enabled", Boolean.toString(enabled));
      row.put("mode", guard.getMode().name());
      row.put("topology", guard.getTopologyKind().name());
      row.put("state", guard.getState().name());
      row.put("experimental_only",
          Boolean.toString(capability.isExperimentalOnly()));
      row.put("reason", reason(guard, capability, enabled));
      rows.add(row);
    }
    return rows;
  }

  private static String replicas(RegionMetadata region) {
    StringBuilder builder = new StringBuilder();
    for (VirtualNodeReplica replica : region.getReplicaMetadata()
        .getReplicas()) {
      if (builder.length() > 0) {
        builder.append(',');
      }
      builder.append(replica.getReplicaId()).append(':')
          .append(replica.getRole().name());
    }
    return builder.toString();
  }

  private static boolean isEnabled(AdbProductionGuard guard,
      AdbProductionCapability capability) {
    if (guard.getState() == AdbProductionState.REJECTED) {
      return false;
    }
    if (capability.isExperimentalOnly()) {
      return guard.getMode() == AdbProductionMode.EXPERIMENTAL
          && guard.isAllowExperimental();
    }
    switch (capability) {
      case LOCAL_SQL:
        return true;
      case DISTRIBUTED_SQL:
      case SINGLE_REGION_TRANSACTION:
      case BASIC_ONLINE_DDL:
      case BACKUP_RESTORE:
      case ROLLING_UPGRADE:
        return guard.getState() == AdbProductionState.CLUSTER_READY;
      default:
        return false;
    }
  }

  private static String reason(AdbProductionGuard guard,
      AdbProductionCapability capability, boolean enabled) {
    if (enabled) {
      return "";
    }
    if (guard.getState() == AdbProductionState.REJECTED) {
      return guard.getRejectionReason();
    }
    if (capability.isExperimentalOnly()) {
      return "experimental capability is disabled";
    }
    if (capability == AdbProductionCapability.LOCAL_SQL) {
      return "";
    }
    return "cluster capability is not available in state="
        + guard.getState();
  }
}
