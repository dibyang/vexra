package net.xdob.vexra.ha;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 虚节点 HA 元数据回归测试。
 *
 * <p>该测试聚焦 data/witness/learner 副本角色是否可展示、可校验，
 * 不涉及真实 Raft 配置变更或网络复制。</p>
 */
class VirtualNodeMetadataTest {
  /**
   * 验证副本角色的能力矩阵与设计文档一致。
   */
  @Test
  void shouldExposeReplicaRoleCapabilities() {
    assertTrue(ReplicaRole.DATA_VOTER.storesData());
    assertTrue(ReplicaRole.DATA_VOTER.isVoter());
    assertTrue(ReplicaRole.DATA_VOTER.canLead());

    assertFalse(ReplicaRole.WITNESS_VOTER.storesData());
    assertTrue(ReplicaRole.WITNESS_VOTER.isVoter());
    assertFalse(ReplicaRole.WITNESS_VOTER.canLead());

    assertTrue(ReplicaRole.LEARNER.storesData());
    assertFalse(ReplicaRole.LEARNER.isVoter());
    assertFalse(ReplicaRole.LEARNER.canLead());
  }

  /**
   * 验证 2 data + 1 witness 拓扑可以被元数据对象表达和统计。
   */
  @Test
  void shouldDescribeTwoDataOneWitnessTopology() {
    VirtualNodeMetadata metadata = new VirtualNodeMetadata(
        "vn-1", 2, "node-a",
        Arrays.asList(
            new VirtualNodeReplica("node-a", ReplicaRole.DATA_VOTER),
            new VirtualNodeReplica("node-b", ReplicaRole.DATA_VOTER),
            new VirtualNodeReplica("witness-a", ReplicaRole.WITNESS_VOTER),
            new VirtualNodeReplica("learner-a", ReplicaRole.LEARNER)),
        10, 3, 0);

    assertEquals("vn-1", metadata.getVirtualNodeId());
    assertEquals(3, metadata.voterCount());
    assertTrue(metadata.hasWitness());
    assertTrue(metadata.isTwoDataOneWitnessTopology());
    assertEquals("node-a", metadata.getLeaderReplica().get().getReplicaId());
  }

  /**
   * 验证 witness 和 learner 不能被设置为 leader。
   */
  @Test
  void shouldRejectNonDataLeader() {
    assertThrows(IllegalArgumentException.class,
        () -> new VirtualNodeMetadata(
            "vn-1", 1, "witness-a",
            Arrays.asList(
                new VirtualNodeReplica("node-a", ReplicaRole.DATA_VOTER),
                new VirtualNodeReplica("witness-a", ReplicaRole.WITNESS_VOTER)),
            0, 0, 0));

    assertThrows(IllegalArgumentException.class,
        () -> new VirtualNodeMetadata(
            "vn-1", 1, "learner-a",
            Arrays.asList(
                new VirtualNodeReplica("node-a", ReplicaRole.DATA_VOTER),
                new VirtualNodeReplica("learner-a", ReplicaRole.LEARNER)),
            0, 0, 0));
  }

  /**
   * 验证重复副本、空副本列表和负数版本/进度会被拒绝。
   */
  @Test
  void shouldRejectInvalidMetadata() {
    assertThrows(IllegalArgumentException.class,
        () -> new VirtualNodeMetadata(
            "vn-1", 1, "",
            Arrays.asList(
                new VirtualNodeReplica("node-a", ReplicaRole.DATA_VOTER),
                new VirtualNodeReplica("node-a", ReplicaRole.LEARNER)),
            0, 0, 0));

    assertThrows(IllegalArgumentException.class,
        () -> new VirtualNodeMetadata(
            "vn-1", 1, "", Collections.emptyList(), 0, 0, 0));

    assertThrows(IllegalArgumentException.class,
        () -> new VirtualNodeMetadata(
            "vn-1", -1, "",
            Collections.singletonList(
                new VirtualNodeReplica("node-a", ReplicaRole.DATA_VOTER)),
            0, 0, 0));
  }
}
