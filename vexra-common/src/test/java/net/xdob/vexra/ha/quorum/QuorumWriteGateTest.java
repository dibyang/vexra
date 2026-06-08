package net.xdob.vexra.ha.quorum;

import net.xdob.vexra.ha.ReplicaRole;
import net.xdob.vexra.ha.VirtualNodeMetadata;
import net.xdob.vexra.ha.VirtualNodeReplica;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多数派写入 gate 回归测试。
 *
 * <p>测试只验证 gate 计算规则：data/witness voter 计入多数派，learner 不计票，
 * leader 必须与元数据一致且具备写入资格。</p>
 */
class QuorumWriteGateTest {
  private final QuorumWriteGate gate = new QuorumWriteGate();

  /**
   * 验证 2 data + 1 witness 中 leader+witness 可以组成多数派并放行写入。
   */
  @Test
  void shouldAllowWriteWithDataAndWitnessQuorum() {
    WriteGateDecision decision = gate.evaluate(metadata(), "node-a",
        Arrays.asList("node-a", "witness-a"));

    assertTrue(decision.isAllowed());
    assertEquals(2, decision.getRequiredQuorum());
    assertEquals(2, decision.getAcknowledgedVoters());
  }

  /**
   * 验证只有 leader 自身确认时无法满足多数派。
   */
  @Test
  void shouldRejectWriteWithoutQuorum() {
    WriteGateDecision decision = gate.evaluate(metadata(), "node-a",
        Arrays.asList("node-a"));

    assertFalse(decision.isAllowed());
    assertEquals("quorum is not satisfied", decision.getReason());
    assertEquals(2, decision.getRequiredQuorum());
    assertEquals(1, decision.getAcknowledgedVoters());
  }

  /**
   * 验证 learner ack 不参与投票计数，不能补足多数派。
   */
  @Test
  void shouldIgnoreLearnerAcknowledgement() {
    WriteGateDecision decision = gate.evaluate(metadata(), "node-a",
        Arrays.asList("node-a", "learner-a"));

    assertFalse(decision.isAllowed());
    assertEquals(1, decision.getAcknowledgedVoters());
  }

  /**
   * 验证请求 leader 与元数据 leader 不一致时拒绝写入。
   */
  @Test
  void shouldRejectMismatchedLeader() {
    WriteGateDecision decision = gate.evaluate(metadata(), "node-b",
        Arrays.asList("node-b", "witness-a"));

    assertFalse(decision.isAllowed());
    assertEquals("leader does not match metadata", decision.getReason());
  }

  /**
   * 验证 requireWritable 在 gate 拒绝时抛出异常，便于写路径快速中断。
   */
  @Test
  void shouldThrowWhenRequireWritableWithoutQuorum() {
    assertThrows(IllegalStateException.class,
        () -> gate.requireWritable(metadata(), "node-a",
            Arrays.asList("node-a")));
  }

  private static VirtualNodeMetadata metadata() {
    return new VirtualNodeMetadata(
        "vn-1", 1, "node-a",
        Arrays.asList(
            new VirtualNodeReplica("node-a", ReplicaRole.DATA_VOTER),
            new VirtualNodeReplica("node-b", ReplicaRole.DATA_VOTER),
            new VirtualNodeReplica("witness-a", ReplicaRole.WITNESS_VOTER),
            new VirtualNodeReplica("learner-a", ReplicaRole.LEARNER)),
        10, 2, 0);
  }
}
