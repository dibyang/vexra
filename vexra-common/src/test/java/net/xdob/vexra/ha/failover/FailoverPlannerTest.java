package net.xdob.vexra.ha.failover;

import net.xdob.vexra.ha.ReplicaRole;
import net.xdob.vexra.ha.VirtualNodeMetadata;
import net.xdob.vexra.ha.VirtualNodeReplica;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多数派故障切换规划回归测试。
 *
 * <p>测试验证 2 data + 1 witness 中任一 data 失败后，剩余 data+witness 可以规划为可写；
 * 无多数派或无 data voter 时必须拒绝自动接管。</p>
 */
class FailoverPlannerTest {
  private final FailoverPlanner planner = new FailoverPlanner();

  /**
   * 验证 leader 可达且满足多数派时不发生切主。
   */
  @Test
  void shouldKeepCurrentLeaderWhenReachableWithQuorum() {
    FailoverDecision decision = planner.plan(metadata(),
        Arrays.asList("node-a", "node-b"));

    assertEquals(FailoverStatus.KEEP_LEADER, decision.getStatus());
    assertTrue(decision.isWritable());
    assertEquals("node-a", decision.getLeaderId());
    assertEquals(1, decision.getMetadata().getEpoch());
  }

  /**
   * 验证 data A 宕机时，data B + witness 可以规划为新 leader 并保持可写。
   */
  @Test
  void shouldPromoteReachableDataWithWitnessQuorum() {
    FailoverDecision decision = planner.plan(metadata(),
        Arrays.asList("node-b", "witness-a"));

    assertEquals(FailoverStatus.PROMOTE_DATA_LEADER, decision.getStatus());
    assertTrue(decision.isWritable());
    assertEquals("node-b", decision.getLeaderId());
    assertEquals(2, decision.getMetadata().getEpoch());
    assertEquals("node-b", decision.getMetadata().getLeaderId());
  }

  /**
   * 验证只有一个 data 可达且没有 witness 时无法满足多数派，只能降级为不可写。
   */
  @Test
  void shouldDegradeWithoutQuorum() {
    FailoverDecision decision = planner.plan(metadata(),
        Arrays.asList("node-b"));

    assertEquals(FailoverStatus.DEGRADED_READONLY, decision.getStatus());
    assertFalse(decision.isWritable());
    assertEquals("quorum is not satisfied", decision.getReason());
  }

  /**
   * 验证只有 witness 可达时不能自动接管为 leader。
   */
  @Test
  void shouldRejectWitnessOnlyFailover() {
    FailoverDecision decision = planner.plan(metadata(),
        Arrays.asList("witness-a"));

    assertEquals(FailoverStatus.UNAVAILABLE, decision.getStatus());
    assertFalse(decision.isWritable());
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
