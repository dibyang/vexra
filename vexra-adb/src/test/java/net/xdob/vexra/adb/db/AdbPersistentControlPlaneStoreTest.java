package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.ldb.LdbStore;
import net.xdob.vexra.cluster.region.KeyRange;
import net.xdob.vexra.cluster.region.RegionMetadata;
import net.xdob.vexra.ha.ReplicaRole;
import net.xdob.vexra.ha.VirtualNodeMetadata;
import net.xdob.vexra.ha.VirtualNodeReplica;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `AdbPersistentControlPlaneStore` 的 GA-03 持久化闭环测试。
 *
 * <p>这些测试覆盖控制面最小生产化的关键证据：节点心跳、region 快照、route epoch
 * 和 TSO 都写入本地 META CF，并且关闭重开后仍能恢复。</p>
 */
class AdbPersistentControlPlaneStoreTest {
  @TempDir
  File tempDir;

  /**
   * 验证 region 快照和 route epoch 可以跨 store 重开恢复。
   *
   * @throws Exception store 创建、发布或读取失败时抛出
   */
  @Test
  void shouldPersistRegionSnapshotAcrossReopen() throws Exception {
    File dbDir = new File(tempDir, "regions");
    long routeEpoch;
    try (LdbStore store = new LdbStore(dbDir.getAbsolutePath())) {
      AdbPersistentControlPlaneStore controlPlane =
          new AdbPersistentControlPlaneStore(store, 100);
      routeEpoch = controlPlane.publishRegions(Collections.singletonList(
          region("r1", bytes("a"), bytes("m"), "node-a")));
    }

    try (LdbStore reopened = new LdbStore(dbDir.getAbsolutePath())) {
      AdbPersistentControlPlaneStore controlPlane =
          new AdbPersistentControlPlaneStore(reopened, 100);
      AdbControlPlaneSnapshot snapshot = controlPlane.getSnapshot();

      assertEquals(routeEpoch, snapshot.getRouteEpoch());
      assertEquals(1, snapshot.getRegions().size());
      assertEquals("r1", snapshot.getRegions().get(0).getRegionId());
      assertEquals("node-a", snapshot.getRegions().get(0)
          .getReplicaMetadata().getLeaderId());
    }
  }

  /**
   * 验证 route epoch 在每次发布 region 快照时单调推进。
   *
   * @throws Exception store 创建或发布失败时抛出
   */
  @Test
  void shouldAdvanceRouteEpochWhenPublishingRegions() throws Exception {
    try (LdbStore store = new LdbStore(new File(tempDir, "epoch")
        .getAbsolutePath())) {
      AdbPersistentControlPlaneStore controlPlane =
          new AdbPersistentControlPlaneStore(store, 0);

      long first = controlPlane.publishRegions(Collections.singletonList(
          region("r1", bytes("a"), bytes("m"), "node-a")));
      long second = controlPlane.publishRegions(Arrays.asList(
          region("r1-left", bytes("a"), bytes("g"), "node-a"),
          region("r1-right", bytes("g"), bytes("m"), "node-b")));

      assertEquals(first + 1, second);
      assertEquals(second, controlPlane.getSnapshot().getRouteEpoch());
      assertEquals(2, controlPlane.getSnapshot().getRegions().size());
    }
  }

  /**
   * 验证 TSO 关闭重开后不回退。
   *
   * @throws Exception store 创建或 TSO 分配失败时抛出
   */
  @Test
  void shouldKeepTimestampMonotonicAfterReopen() throws Exception {
    File dbDir = new File(tempDir, "tso");
    long second;
    try (LdbStore store = new LdbStore(dbDir.getAbsolutePath())) {
      AdbPersistentControlPlaneStore controlPlane =
          new AdbPersistentControlPlaneStore(store, 1000);

      assertEquals(1001, controlPlane.nextTimestamp());
      second = controlPlane.nextTimestamp();
    }

    try (LdbStore reopened = new LdbStore(dbDir.getAbsolutePath())) {
      AdbPersistentControlPlaneStore controlPlane =
          new AdbPersistentControlPlaneStore(reopened, 1);

      assertTrue(controlPlane.nextTimestamp() > second);
    }
  }

  /**
   * 验证节点心跳会持久化为 UP 状态的控制面节点记录。
   *
   * @throws Exception store 创建、心跳写入或读取失败时抛出
   */
  @Test
  void shouldPersistHeartbeatNodeRecordAcrossReopen() throws Exception {
    File dbDir = new File(tempDir, "nodes");
    try (LdbStore store = new LdbStore(dbDir.getAbsolutePath())) {
      AdbPersistentControlPlaneStore controlPlane =
          new AdbPersistentControlPlaneStore(store, 0);
      controlPlane.heartbeat(new AdbNodeHeartbeat("node-a",
          AdbDeploymentNodeRole.DATA_NODE, "127.0.0.1", 17001,
          11, 10, 123456, "rack-a"));
    }

    try (LdbStore reopened = new LdbStore(dbDir.getAbsolutePath())) {
      AdbPersistentControlPlaneStore controlPlane =
          new AdbPersistentControlPlaneStore(reopened, 0);
      Optional<AdbControlPlaneNodeRecord> node =
          controlPlane.getNode("node-a");

      assertTrue(node.isPresent());
      assertEquals(AdbDeploymentNodeRole.DATA_NODE, node.get().getRole());
      assertEquals(AdbControlPlaneNodeStatus.UP, node.get().getStatus());
      assertEquals("127.0.0.1", node.get().getHost());
      assertEquals(17001, node.get().getPort());
      assertEquals(123456, node.get().getLastHeartbeatMillis());
      assertEquals("rack-a", node.get().getFailureDomain());
    }
  }

  /**
   * 验证重复心跳会覆盖同一节点，而不是生成重复记录。
   *
   * @throws Exception store 创建、心跳写入或读取失败时抛出
   */
  @Test
  void shouldReplaceNodeRecordByNodeId() throws Exception {
    try (LdbStore store = new LdbStore(new File(tempDir, "replace")
        .getAbsolutePath())) {
      AdbPersistentControlPlaneStore controlPlane =
          new AdbPersistentControlPlaneStore(store, 0);

      controlPlane.heartbeat(new AdbNodeHeartbeat("node-a",
          AdbDeploymentNodeRole.DATA_NODE, "127.0.0.1", 17001,
          1, 1, 100, "rack-a"));
      controlPlane.heartbeat(new AdbNodeHeartbeat("node-a",
          AdbDeploymentNodeRole.DATA_NODE, "127.0.0.2", 17002,
          3, 2, 200, "rack-b"));

      List<AdbControlPlaneNodeRecord> nodes = controlPlane.listNodes();
      assertEquals(1, nodes.size());
      assertEquals("127.0.0.2", nodes.get(0).getHost());
      assertEquals(3, nodes.get(0).getCommitIndex());
      assertEquals(2, nodes.get(0).getAppliedIndex());
      assertEquals("rack-b", nodes.get(0).getFailureDomain());
    }
  }

  /**
   * 验证 heartbeat service 会先把超时节点推进到 SUSPECT。
   *
   * @throws Exception store 创建、心跳写入或状态评估失败时抛出
   */
  @Test
  void shouldMoveNodeToSuspectAfterHeartbeatTimeout() throws Exception {
    try (LdbStore store = new LdbStore(new File(tempDir, "suspect")
        .getAbsolutePath())) {
      AdbPersistentControlPlaneStore controlPlane =
          new AdbPersistentControlPlaneStore(store, 0);
      AdbNodeHeartbeatService service =
          new AdbNodeHeartbeatService(controlPlane, 100, 300);

      service.heartbeat(new AdbNodeHeartbeat("node-a",
          AdbDeploymentNodeRole.DATA_NODE, "127.0.0.1", 17001,
          1, 1, 1000, "rack-a"));

      assertEquals(1, service.evaluateTimeouts(1100));
      assertEquals(AdbControlPlaneNodeStatus.SUSPECT,
          controlPlane.getNode("node-a").get().getStatus());
    }
  }

  /**
   * 验证 heartbeat service 会把长时间未心跳节点推进到 DOWN。
   *
   * @throws Exception store 创建、心跳写入或状态评估失败时抛出
   */
  @Test
  void shouldMoveNodeToDownAfterFailureThreshold() throws Exception {
    try (LdbStore store = new LdbStore(new File(tempDir, "down")
        .getAbsolutePath())) {
      AdbPersistentControlPlaneStore controlPlane =
          new AdbPersistentControlPlaneStore(store, 0);
      AdbNodeHeartbeatService service =
          new AdbNodeHeartbeatService(controlPlane, 100, 300);

      service.heartbeat(new AdbNodeHeartbeat("node-a",
          AdbDeploymentNodeRole.DATA_NODE, "127.0.0.1", 17001,
          1, 1, 1000, "rack-a"));

      assertEquals(1, service.evaluateTimeouts(1300));
      assertEquals(AdbControlPlaneNodeStatus.DOWN,
          controlPlane.getNode("node-a").get().getStatus());
    }
  }

  /**
   * 验证节点恢复心跳后会回到 UP。
   *
   * @throws Exception store 创建、心跳写入或状态评估失败时抛出
   */
  @Test
  void shouldRecoverNodeToUpWhenHeartbeatArrives() throws Exception {
    try (LdbStore store = new LdbStore(new File(tempDir, "recover")
        .getAbsolutePath())) {
      AdbPersistentControlPlaneStore controlPlane =
          new AdbPersistentControlPlaneStore(store, 0);
      AdbNodeHeartbeatService service =
          new AdbNodeHeartbeatService(controlPlane, 100, 300);

      service.heartbeat(new AdbNodeHeartbeat("node-a",
          AdbDeploymentNodeRole.DATA_NODE, "127.0.0.1", 17001,
          1, 1, 1000, "rack-a"));
      service.evaluateTimeouts(1300);
      service.heartbeat(new AdbNodeHeartbeat("node-a",
          AdbDeploymentNodeRole.DATA_NODE, "127.0.0.1", 17001,
          2, 2, 1310, "rack-a"));

      assertEquals(AdbControlPlaneNodeStatus.UP,
          controlPlane.getNode("node-a").get().getStatus());
      assertEquals(2, controlPlane.getNode("node-a").get()
          .getCommitIndex());
    }
  }

  /**
   * 验证后台超时评估不会覆盖显式运维状态。
   *
   * @throws Exception store 创建、记录写入或状态评估失败时抛出
   */
  @Test
  void shouldKeepExplicitOperationalStateDuringTimeoutEvaluation()
      throws Exception {
    try (LdbStore store = new LdbStore(new File(tempDir, "explicit")
        .getAbsolutePath())) {
      AdbPersistentControlPlaneStore controlPlane =
          new AdbPersistentControlPlaneStore(store, 0);
      AdbNodeHeartbeatService service =
          new AdbNodeHeartbeatService(controlPlane, 100, 300);
      controlPlane.persistNodeRecord(new AdbControlPlaneNodeRecord("node-a",
          AdbDeploymentNodeRole.DATA_NODE, "127.0.0.1", 17001,
          AdbControlPlaneNodeStatus.RECOVERING, 1000, 1, 1, "rack-a"));

      assertEquals(0, service.evaluateTimeouts(2000));
      assertEquals(AdbControlPlaneNodeStatus.RECOVERING,
          controlPlane.getNode("node-a").get().getStatus());
    }
  }

  /**
   * 验证 system table provider 可以输出控制面节点行。
   *
   * @throws Exception store 创建、心跳写入或行输出失败时抛出
   */
  @Test
  void shouldExposeNodesAsSystemTableRows() throws Exception {
    try (LdbStore store = new LdbStore(new File(tempDir, "sys-nodes")
        .getAbsolutePath())) {
      AdbPersistentControlPlaneStore controlPlane =
          new AdbPersistentControlPlaneStore(store, 0);
      controlPlane.heartbeat(new AdbNodeHeartbeat("node-a",
          AdbDeploymentNodeRole.DATA_NODE, "127.0.0.1", 17001,
          11, 10, 123456, "rack-a"));

      List<Map<String, String>> rows =
          new AdbSystemTableProvider(controlPlane).nodes();

      assertEquals(1, rows.size());
      assertEquals("node-a", rows.get(0).get("node_id"));
      assertEquals("DATA_NODE", rows.get(0).get("role"));
      assertEquals("UP", rows.get(0).get("status"));
      assertEquals("rack-a", rows.get(0).get("failure_domain"));
      assertEquals("11", rows.get(0).get("commit_index"));
    }
  }

  /**
   * 验证 system table provider 可以输出 region 行和 route epoch。
   *
   * @throws Exception store 创建、region 发布或行输出失败时抛出
   */
  @Test
  void shouldExposeRegionsAsSystemTableRows() throws Exception {
    try (LdbStore store = new LdbStore(new File(tempDir, "sys-regions")
        .getAbsolutePath())) {
      AdbPersistentControlPlaneStore controlPlane =
          new AdbPersistentControlPlaneStore(store, 0);
      long routeEpoch = controlPlane.publishRegions(Collections.singletonList(
          region("r1", bytes("a"), bytes("m"), "node-a")));

      List<Map<String, String>> rows =
          new AdbSystemTableProvider(controlPlane).regions();

      assertEquals(1, rows.size());
      assertEquals("r1", rows.get(0).get("region_id"));
      assertEquals(Long.toString(routeEpoch), rows.get(0)
          .get("route_epoch"));
      assertEquals("node-a", rows.get(0).get("leader_id"));
      assertEquals("ACTIVE", rows.get(0).get("state"));
      assertTrue(rows.get(0).get("replicas").contains(
          "node-w:WITNESS_VOTER"));
    }
  }

  /**
   * 验证 system table provider 可以输出 TSO 初始化状态和最新时间戳。
   *
   * @throws Exception store 创建、TSO 分配或行输出失败时抛出
   */
  @Test
  void shouldExposeTsoAsSystemTableRows() throws Exception {
    try (LdbStore store = new LdbStore(new File(tempDir, "sys-tso")
        .getAbsolutePath())) {
      AdbPersistentControlPlaneStore controlPlane =
          new AdbPersistentControlPlaneStore(store, 100);
      AdbSystemTableProvider provider = new AdbSystemTableProvider(
          controlPlane);

      List<Map<String, String>> before = provider.tso();
      assertEquals("false", before.get(0).get("initialized"));
      assertEquals("", before.get(0).get("last_issued_ts"));

      assertEquals(101, controlPlane.nextTimestamp());
      List<Map<String, String>> after = provider.tso();

      assertEquals("true", after.get(0).get("initialized"));
      assertEquals("101", after.get(0).get("last_issued_ts"));
      assertEquals("global", after.get(0).get("scope"));
    }
  }

  /**
   * 验证控制面 lease 和 config 可以跨 store 重开恢复。
   *
   * @throws Exception store 创建、写入或读取失败时抛出
   */
  @Test
  void shouldPersistLeasesAndConfigsAcrossReopen() throws Exception {
    File dbDir = new File(tempDir, "lease-config");
    try (LdbStore store = new LdbStore(dbDir.getAbsolutePath())) {
      AdbPersistentControlPlaneStore controlPlane =
          new AdbPersistentControlPlaneStore(store, 0);
      controlPlane.persistLease(new AdbControlPlaneLeaseRecord(
          "tso-owner", "node-a", 7, 5000, 99));
      controlPlane.persistConfig(new AdbControlPlaneConfigRecord(
          "adb.production.mode", "mvp-cluster", 3, 1234));
    }

    try (LdbStore reopened = new LdbStore(dbDir.getAbsolutePath())) {
      AdbPersistentControlPlaneStore controlPlane =
          new AdbPersistentControlPlaneStore(reopened, 0);

      assertEquals(1, controlPlane.listLeases().size());
      assertEquals("tso-owner", controlPlane.listLeases().get(0)
          .getLeaseName());
      assertEquals("node-a", controlPlane.listLeases().get(0).getOwner());
      assertEquals(99, controlPlane.listLeases().get(0).getFencingToken());
      assertEquals(1, controlPlane.listConfigs().size());
      assertEquals("mvp-cluster", controlPlane.listConfigs().get(0)
          .getValue());
    }
  }

  /**
   * 验证 system table provider 可以输出 lease 和 config 行。
   *
   * @throws Exception store 创建、写入或行输出失败时抛出
   */
  @Test
  void shouldExposeLeasesAndConfigsAsSystemTableRows() throws Exception {
    try (LdbStore store = new LdbStore(new File(tempDir, "sys-lease")
        .getAbsolutePath())) {
      AdbPersistentControlPlaneStore controlPlane =
          new AdbPersistentControlPlaneStore(store, 0);
      controlPlane.persistLease(new AdbControlPlaneLeaseRecord(
          "gc-worker", "node-a", 2, 2000, 11));
      controlPlane.persistConfig(new AdbControlPlaneConfigRecord(
          "adb.production.mode", "mvp-cluster", 3, 1200));
      AdbSystemTableProvider provider = new AdbSystemTableProvider(
          controlPlane);

      List<Map<String, String>> leases = provider.leases(1500);
      assertEquals(1, leases.size());
      assertEquals("gc-worker", leases.get(0).get("lease_name"));
      assertEquals("node-a", leases.get(0).get("owner"));
      assertEquals("true", leases.get(0).get("active"));
      assertEquals("11", leases.get(0).get("fencing_token"));

      List<Map<String, String>> configs = provider.configs();
      assertEquals(1, configs.size());
      assertEquals("adb.production.mode", configs.get(0).get("config_key"));
      assertEquals("mvp-cluster", configs.get(0).get("value"));
      assertEquals("3", configs.get(0).get("version"));
    }
  }

  /**
   * 验证 capability system table 可以反映生产 guard 的启用和拒绝原因。
   *
   * @throws Exception 行输出失败时抛出
   */
  @Test
  void shouldExposeCapabilitiesAsSystemTableRows() throws Exception {
    try (LdbStore store = new LdbStore(new File(tempDir, "sys-cap")
        .getAbsolutePath())) {
      AdbPersistentControlPlaneStore controlPlane =
          new AdbPersistentControlPlaneStore(store, 0);
      AdbSystemTableProvider provider = new AdbSystemTableProvider(
          controlPlane);
      AdbProductionGuard guard = new AdbProductionGuard(
          AdbProductionMode.MVP_CLUSTER,
          AdbProductionTopologyKind.TWO_DATA_ONE_WITNESS,
          true, true, true, false);

      List<Map<String, String>> rows = provider.capabilities(guard);

      assertEquals("true", findCapability(rows,
          AdbProductionCapability.DISTRIBUTED_SQL).get("enabled"));
      assertEquals("true", findCapability(rows,
          AdbProductionCapability.SINGLE_REGION_TRANSACTION).get("enabled"));
      Map<String, String> crossRegion = findCapability(rows,
          AdbProductionCapability.CROSS_REGION_TRANSACTION);
      assertEquals("false", crossRegion.get("enabled"));
      assertEquals("experimental capability is disabled",
          crossRegion.get("reason"));
    }
  }

  private static RegionMetadata region(String regionId, byte[] startKey,
      byte[] endKey, String leaderId) {
    return new RegionMetadata(regionId, new KeyRange(startKey, endKey), 1,
        new VirtualNodeMetadata(regionId + "-vn", 1, leaderId, Arrays.asList(
            new VirtualNodeReplica("node-a", ReplicaRole.DATA_VOTER),
            new VirtualNodeReplica("node-b", ReplicaRole.DATA_VOTER),
            new VirtualNodeReplica("node-w", ReplicaRole.WITNESS_VOTER)),
            12, 2, 0));
  }

  private static byte[] bytes(String value) {
    return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  private static Map<String, String> findCapability(
      List<Map<String, String>> rows, AdbProductionCapability capability) {
    for (Map<String, String> row : rows) {
      if (capability.name().equals(row.get("capability"))) {
        return row;
      }
    }
    throw new AssertionError("missing capability row: " + capability);
  }
}
