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
}
