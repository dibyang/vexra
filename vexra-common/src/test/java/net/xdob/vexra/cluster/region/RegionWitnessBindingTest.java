package net.xdob.vexra.cluster.region;

import net.xdob.vexra.ha.ReplicaRole;
import net.xdob.vexra.ha.VirtualNodeMetadata;
import net.xdob.vexra.ha.VirtualNodeReplica;
import net.xdob.vexra.ha.failover.FailoverStatus;
import net.xdob.vexra.ha.witness.FileWitnessStateStore;
import net.xdob.vexra.ha.witness.WitnessState;
import net.xdob.vexra.ha.witness.WitnessStateManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Region witness 运行时绑定回归测试。
 *
 * <p>测试验证 region 层可以复用 witness HA 的写入 fencing、故障切换规划和投票持久化。</p>
 */
class RegionWitnessBindingTest {
  @TempDir
  Path tempDir;

  /**
   * 验证 region 写入在 data+witness 多数派下放行，缺少多数派时拒绝。
   */
  @Test
  void shouldFenceRegionWritesByQuorum() {
    RegionWitnessBinding binding = binding();
    RegionMetadata region = region();

    assertDoesNotThrow(() -> binding.fenceWrite(region, "node-a",
        Arrays.asList("node-a", "witness-a")));
    assertThrows(IllegalStateException.class,
        () -> binding.fenceWrite(region, "node-a",
            Arrays.asList("node-a")));
  }

  /**
   * 验证 region 层可规划 data+witness 故障切换。
   */
  @Test
  void shouldPlanRegionFailoverWithWitness() {
    RegionWitnessBinding binding = binding();

    assertEquals(FailoverStatus.PROMOTE_DATA_LEADER,
        binding.planFailover(region(), Arrays.asList("node-b", "witness-a"))
            .getStatus());
  }

  /**
   * 验证 witness vote 通过 region 虚节点 ID 持久化。
   */
  @Test
  void shouldPersistWitnessVoteByRegionVirtualNode() throws Exception {
    RegionWitnessBinding binding = binding();
    RegionMetadata region = region();

    WitnessState state = binding.grantWitnessVote(region, "node-b", 5);
    WitnessState reloaded = new FileWitnessStateStore(tempDir)
        .load(region.getReplicaMetadata().getVirtualNodeId());

    assertEquals(5, state.getCurrentTerm());
    assertEquals("node-b", reloaded.getVotedFor());
    assertFalse(reloaded.canGrantVote("node-a", 5));
  }

  private RegionWitnessBinding binding() {
    return new RegionWitnessBinding(new WitnessStateManager(
        new FileWitnessStateStore(tempDir)));
  }

  private static RegionMetadata region() {
    return new RegionMetadata("r1",
        new KeyRange(bytes("a"), bytes("z")), 1,
        new VirtualNodeMetadata("vn-r1", 1, "node-a",
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
