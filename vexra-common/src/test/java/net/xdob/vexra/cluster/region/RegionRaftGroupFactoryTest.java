package net.xdob.vexra.cluster.region;

import net.xdob.vexra.ha.ReplicaRole;
import net.xdob.vexra.ha.VirtualNodeMetadata;
import net.xdob.vexra.ha.VirtualNodeReplica;
import net.xdob.vexra.protocol.RaftPeer;
import net.xdob.vexra.protocol.SetConfigurationRequest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Region RaftGroup 绑定回归测试。
 *
 * <p>测试覆盖 ADB-Cluster-02 的公共模型：region 可以映射到现有 RaftGroup，
 * 成员变更可以生成 SetConfiguration 参数，snapshot install 计划保留 term/index。</p>
 */
class RegionRaftGroupFactoryTest {
  private final RegionRaftGroupFactory factory = new RegionRaftGroupFactory();

  /**
   * 验证 data voter 映射为 RaftGroup peer，witness voter 作为扩展元数据保留。
   */
  @Test
  void shouldCreateRaftGroupFromRegionDataVoters() {
    RegionRaftGroupDescriptor descriptor = factory.create(region(false), peers());

    assertEquals("region-r1", descriptor.getRaftGroup().getGroupId().toString());
    assertEquals(2, descriptor.getRaftGroup().getPeers().size());
    assertEquals(2, descriptor.getDataVoters().size());
    assertEquals(1, descriptor.getWitnessVoterIds().size());
    assertEquals("witness-a", descriptor.getWitnessVoterIds().get(0));
  }

  /**
   * 验证 learner 可以进入配置变更 listener 集合，便于后续追赶和扩容。
   */
  @Test
  void shouldBuildMembershipChangeArguments() {
    RegionRaftGroupDescriptor current = factory.create(region(false), peers());
    RegionRaftGroupDescriptor target = factory.create(region(true), peers());

    SetConfigurationRequest.Arguments arguments =
        new RegionMembershipChangePlan(current, target)
            .toSetConfigurationArguments();

    assertEquals(SetConfigurationRequest.Mode.COMPARE_AND_SET,
        arguments.getMode());
    assertEquals(2, arguments.getServersInCurrentConf().size());
    assertEquals(2, arguments.getServersInNewConf().size());
    assertEquals(0, arguments.getListenersInCurrentConf().size());
    assertEquals(1, arguments.getPeersInNewConf(
        net.xdob.vexra.proto.raft.RaftPeerRole.LISTENER).size());
  }

  /**
   * 验证缺少 data voter 的 RaftPeer 映射时会失败，避免生成不完整 region group。
   */
  @Test
  void shouldRejectMissingPeerMapping() {
    Map<String, RaftPeer> peers = peers();
    peers.remove("node-b");

    assertThrows(IllegalArgumentException.class,
        () -> factory.create(region(false), peers));
  }

  /**
   * 验证 snapshot install 计划保留 region、term、index 和目标副本。
   */
  @Test
  void shouldDescribeSnapshotInstallPlan() {
    RegionSnapshotInstallPlan plan = new RegionSnapshotInstallPlan(
        "r1", 3, 10, Arrays.asList("node-b"));

    assertEquals("r1", plan.getRegionId());
    assertEquals(3, plan.getSnapshotTerm());
    assertEquals(10, plan.getSnapshotIndex());
    assertEquals("node-b", plan.getTargetReplicaIds().get(0));
  }

  private static RegionMetadata region(boolean includeLearner) {
    java.util.List<VirtualNodeReplica> replicas = new java.util.ArrayList<>(
        Arrays.asList(
            new VirtualNodeReplica("node-a", ReplicaRole.DATA_VOTER),
            new VirtualNodeReplica("node-b", ReplicaRole.DATA_VOTER),
            new VirtualNodeReplica("witness-a", ReplicaRole.WITNESS_VOTER)));
    if (includeLearner) {
      replicas.add(new VirtualNodeReplica("learner-a", ReplicaRole.LEARNER));
    }
    return new RegionMetadata("r1",
        new KeyRange(bytes("a"), bytes("z")), 1,
        new VirtualNodeMetadata("vn-r1", 1, "node-a",
            replicas, 0, 0, 0));
  }

  private static Map<String, RaftPeer> peers() {
    Map<String, RaftPeer> peers = new HashMap<>();
    peers.put("node-a", peer("node-a", "127.0.0.1:9001"));
    peers.put("node-b", peer("node-b", "127.0.0.1:9002"));
    peers.put("learner-a", peer("learner-a", "127.0.0.1:9003"));
    return peers;
  }

  private static RaftPeer peer(String id, String address) {
    return RaftPeer.newBuilder().setId(id).setAddress(address).build();
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
