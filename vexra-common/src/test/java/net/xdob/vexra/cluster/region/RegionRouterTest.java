package net.xdob.vexra.cluster.region;

import net.xdob.vexra.ha.ReplicaRole;
import net.xdob.vexra.ha.VirtualNodeMetadata;
import net.xdob.vexra.ha.VirtualNodeReplica;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Region 元数据和 range 路由回归测试。
 *
 * <p>测试覆盖 ADB-Cluster-01 的最小验收：单表主键点查和范围扫描可以路由到 region，
 * 且元数据可输出系统表行。</p>
 */
class RegionRouterTest {
  /**
   * 验证主键点查能路由到对应 region。
   */
  @Test
  void shouldRoutePointKeyToRegion() {
    RegionRouter router = router();

    assertEquals("r1", router.route(bytes("a")).getRegionId());
    assertEquals("r2", router.route(bytes("n")).getRegionId());
  }

  /**
   * 验证范围扫描能路由到所有相交 region。
   */
  @Test
  void shouldRouteRangeToOverlappedRegions() {
    RegionRouter router = router();

    List<RegionMetadata> regions = router.route(
        new KeyRange(bytes("b"), bytes("z")));

    assertEquals(2, regions.size());
    assertEquals("r1", regions.get(0).getRegionId());
    assertEquals("r2", regions.get(1).getRegionId());
  }

  /**
   * 验证重叠 region 会在 router 构造时被拒绝。
   */
  @Test
  void shouldRejectOverlappedRegions() {
    RegionMetadata r1 = region("r1", "", "m");
    RegionMetadata r2 = region("r2", "a", "z");

    assertThrows(IllegalArgumentException.class,
        () -> new RegionRouter(Arrays.asList(r1, r2)));
  }

  /**
   * 验证 region 元数据可以输出系统表行。
   */
  @Test
  void shouldExposeSystemTableRow() {
    Map<String, String> row = region("r1", "a", "m").toSystemTableRow();

    assertEquals("r1", row.get("region_id"));
    assertEquals("61", row.get("start_key_hex"));
    assertEquals("6d", row.get("end_key_hex"));
    assertEquals("vn-r1", row.get("virtual_node_id"));
    assertEquals("node-a", row.get("leader_id"));
    assertEquals("3", row.get("voter_count"));
    assertEquals("true", row.get("has_witness"));
  }

  private static RegionRouter router() {
    return new RegionRouter(Arrays.asList(
        region("r1", "", "m"),
        region("r2", "m", "")));
  }

  private static RegionMetadata region(String regionId, String startKey,
      String endKey) {
    return new RegionMetadata(regionId,
        new KeyRange(bytes(startKey), bytes(endKey)), 1,
        new VirtualNodeMetadata(
            "vn-" + regionId, 1, "node-a",
            Arrays.asList(
                new VirtualNodeReplica("node-a", ReplicaRole.DATA_VOTER),
                new VirtualNodeReplica("node-b", ReplicaRole.DATA_VOTER),
                new VirtualNodeReplica("witness-a", ReplicaRole.WITNESS_VOTER)),
            0, 0, 0));
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
