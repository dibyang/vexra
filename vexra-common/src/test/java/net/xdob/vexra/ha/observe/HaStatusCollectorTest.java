package net.xdob.vexra.ha.observe;

import net.xdob.vexra.ha.HaConfig;
import net.xdob.vexra.ha.HaMode;
import net.xdob.vexra.ha.HaNodeRole;
import net.xdob.vexra.ha.ReplicaRole;
import net.xdob.vexra.ha.VirtualNodeMetadata;
import net.xdob.vexra.ha.VirtualNodeReplica;
import net.xdob.vexra.ha.failover.FailoverStatus;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HA 观测快照回归测试。
 *
 * <p>测试确保 HA 状态可以稳定输出给系统表和 metrics，便于后续运维排障和告警。</p>
 */
class HaStatusCollectorTest {
  private final HaStatusCollector collector = new HaStatusCollector();

  /**
   * 验证 data+witness 多数派切主后，观测快照展示可写和新 leader。
   */
  @Test
  void shouldCollectWritableWitnessStatus() {
    HaStatusSnapshot snapshot = collector.collect(config(), metadata(),
        Arrays.asList("node-b", "witness-a"));

    assertEquals(HaMode.WITNESS, snapshot.getMode());
    assertEquals("vn-1", snapshot.getVirtualNodeId());
    assertEquals("node-b", snapshot.getLeaderId());
    assertEquals(2, snapshot.getEpoch());
    assertEquals(3, snapshot.getVoterCount());
    assertEquals(2, snapshot.getReachableVoters());
    assertEquals(2, snapshot.getRequiredQuorum());
    assertTrue(snapshot.isWitnessPresent());
    assertTrue(snapshot.isWritable());
    assertEquals(FailoverStatus.PROMOTE_DATA_LEADER,
        snapshot.getFailoverStatus());
  }

  /**
   * 验证系统表输出字段名和值保持稳定。
   */
  @Test
  void shouldExposeSystemTableRow() {
    Map<String, String> row = collector.collect(config(), metadata(),
        Arrays.asList("node-a", "node-b")).toSystemTableRow();

    assertEquals("WITNESS", row.get("mode"));
    assertEquals("vn-1", row.get("virtual_node_id"));
    assertEquals("node-a", row.get("leader_id"));
    assertEquals("true", row.get("writable"));
    assertEquals("KEEP_LEADER", row.get("failover_status"));
  }

  /**
   * 验证 metrics 输出只包含数值字段，便于接入现有 metrics registry。
   */
  @Test
  void shouldExposeNumericMetrics() {
    Map<String, Number> metrics = collector.collect(config(), metadata(),
        Arrays.asList("node-a")).toMetrics();

    assertEquals(3, metrics.get("vexra_ha_voter_count"));
    assertEquals(1, metrics.get("vexra_ha_reachable_voters"));
    assertEquals(2, metrics.get("vexra_ha_required_quorum"));
    assertEquals(1, metrics.get("vexra_ha_witness_present"));
    assertEquals(0, metrics.get("vexra_ha_writable"));
  }

  private static HaConfig config() {
    return new HaConfig(HaMode.WITNESS, HaNodeRole.DATA, "node-a",
        "127.0.0.1:9876", false, true);
  }

  private static VirtualNodeMetadata metadata() {
    return new VirtualNodeMetadata(
        "vn-1", 1, "node-a",
        Arrays.asList(
            new VirtualNodeReplica("node-a", ReplicaRole.DATA_VOTER),
            new VirtualNodeReplica("node-b", ReplicaRole.DATA_VOTER),
            new VirtualNodeReplica("witness-a", ReplicaRole.WITNESS_VOTER)),
        10, 2, 0);
  }
}
