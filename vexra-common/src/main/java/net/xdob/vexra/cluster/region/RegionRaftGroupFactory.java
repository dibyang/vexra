package net.xdob.vexra.cluster.region;

import net.xdob.vexra.ha.ReplicaRole;
import net.xdob.vexra.ha.VirtualNodeReplica;
import net.xdob.vexra.protocol.RaftGroup;
import net.xdob.vexra.protocol.RaftGroupId;
import net.xdob.vexra.protocol.RaftPeer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Region RaftGroup 绑定工厂。
 *
 * <p>该工厂把 ADB-Cluster region 元数据转换成现有 Vexra RaftGroup。
 * data voter 会成为 RaftGroup 成员；learner 暂时作为 listener 候选保留；
 * witness voter 由于当前 Raft proto 尚无专用角色，只保留标识并交给 witness HA 层处理。</p>
 */
public final class RegionRaftGroupFactory {
  /**
   * 根据 region 元数据创建 RaftGroup 绑定描述。
   *
   * @param region region 元数据
   * @param peersByReplicaId 副本标识到 RaftPeer 的映射
   * @return region RaftGroup 绑定描述
   */
  public RegionRaftGroupDescriptor create(RegionMetadata region,
      Map<String, RaftPeer> peersByReplicaId) {
    Objects.requireNonNull(region, "region == null");
    Objects.requireNonNull(peersByReplicaId, "peersByReplicaId == null");

    List<RaftPeer> dataVoters = new ArrayList<>();
    List<RaftPeer> learners = new ArrayList<>();
    List<String> witnessVoterIds = new ArrayList<>();

    for (VirtualNodeReplica replica
        : region.getReplicaMetadata().getReplicas()) {
      if (replica.getRole() == ReplicaRole.WITNESS_VOTER) {
        witnessVoterIds.add(replica.getReplicaId());
      } else if (replica.getRole() == ReplicaRole.DATA_VOTER) {
        dataVoters.add(peer(peersByReplicaId, replica));
      } else if (replica.getRole() == ReplicaRole.LEARNER) {
        learners.add(peer(peersByReplicaId, replica));
      }
    }
    if (dataVoters.isEmpty()) {
      throw new IllegalArgumentException(
          "region requires at least one data voter: " + region.getRegionId());
    }
    RaftGroup group = RaftGroup.valueOf(groupId(region.getRegionId()),
        dataVoters);
    return new RegionRaftGroupDescriptor(region, group, dataVoters, learners,
        witnessVoterIds);
  }

  /**
   * 生成 region 对应的 RaftGroupId。
   *
   * @param regionId region 标识
   * @return RaftGroupId
   */
  public RaftGroupId groupId(String regionId) {
    if (regionId == null || regionId.trim().isEmpty()) {
      throw new IllegalArgumentException("regionId is empty");
    }
    return RaftGroupId.valueOf("region-" + regionId.trim());
  }

  private static RaftPeer peer(Map<String, RaftPeer> peersByReplicaId,
      VirtualNodeReplica replica) {
    RaftPeer peer = peersByReplicaId.get(replica.getReplicaId());
    if (peer == null) {
      throw new IllegalArgumentException(
          "missing RaftPeer for replica: " + replica.getReplicaId());
    }
    return peer;
  }
}
