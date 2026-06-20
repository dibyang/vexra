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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `AdbControlPlaneServer` 的 GA-03 服务入口测试。
 *
 * <p>测试验证 SQL 层和 region 层通过同一个进程内控制面入口消费 route、heartbeat、
 * TSO 和 system table 事实来源。该测试不启动网络服务，后续 RPC 化时应保持这些语义。</p>
 */
class AdbControlPlaneServerTest {
  @TempDir
  File tempDir;

  /**
   * 验证 server façade 可以串起心跳、route 发布、TSO、watch 和 system table。
   *
   * @throws Exception store 创建或控制面读写失败时抛出
   */
  @Test
  void shouldExposeUnifiedControlPlaneFacade() throws Exception {
    try (LdbStore store = new LdbStore(new File(tempDir, "server")
        .getAbsolutePath())) {
      AdbControlPlaneServer server = new AdbControlPlaneServer(
          new AdbPersistentControlPlaneStore(store, 10), 100, 300);

      server.heartbeat(new AdbNodeHeartbeat("node-a",
          AdbDeploymentNodeRole.DATA_NODE, "127.0.0.1", 17001,
          11, 10, 1000, "rack-a"));
      long routeEpoch = server.publishRegions(Collections.singletonList(
          region("r1", "node-a")));
      long ts = server.nextTimestamp();
      AdbRouteWatch watch = server.watchRoutes(0);

      assertEquals(routeEpoch, server.getSnapshot().getRouteEpoch());
      assertTrue(watch.isRouteChanged());
      assertEquals(11, ts);
      assertEquals("node-a", server.nodes().get(0).get("node_id"));
      assertEquals("UP", server.nodes().get(0).get("status"));
      assertEquals("r1", server.regions().get(0).get("region_id"));
      assertEquals(Long.toString(routeEpoch),
          server.regions().get(0).get("route_epoch"));
      assertEquals(Long.toString(ts),
          server.tso().get(0).get("last_issued_ts"));
    }
  }

  /**
   * 验证 server façade 的心跳状态机输出会反映到 system table。
   *
   * @throws Exception store 创建或控制面读写失败时抛出
   */
  @Test
  void shouldEvaluateHeartbeatTimeoutsThroughFacade() throws Exception {
    try (LdbStore store = new LdbStore(new File(tempDir, "timeouts")
        .getAbsolutePath())) {
      AdbControlPlaneServer server = new AdbControlPlaneServer(
          new AdbPersistentControlPlaneStore(store, 0), 100, 300);

      server.heartbeat(new AdbNodeHeartbeat("node-a",
          AdbDeploymentNodeRole.DATA_NODE, "127.0.0.1", 17001,
          1, 1, 1000, "rack-a"));
      assertEquals(1, server.evaluateHeartbeatTimeouts(1300));

      Map<String, String> node = server.nodes().get(0);
      assertEquals("node-a", node.get("node_id"));
      assertEquals("DOWN", node.get("status"));
    }
  }

  private static RegionMetadata region(String regionId, String leaderId) {
    return new RegionMetadata(regionId, new KeyRange(bytes("a"), bytes("z")),
        1, new VirtualNodeMetadata("v-" + regionId, 1, leaderId,
        Arrays.asList(new VirtualNodeReplica("node-a",
                ReplicaRole.DATA_VOTER),
            new VirtualNodeReplica("node-b", ReplicaRole.DATA_VOTER),
            new VirtualNodeReplica("witness-a",
                ReplicaRole.WITNESS_VOTER)),
        7, 3, 2000));
  }

  private static byte[] bytes(String value) {
    return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }
}
