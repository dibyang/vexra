package net.xdob.vexra.cluster.region;

import net.xdob.vexra.protocol.RaftGroup;
import net.xdob.vexra.protocol.RaftPeer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Region 与现有 RaftGroup 的绑定描述。
 *
 * <p>当前 Raft 协议层还没有 witness voter 角色，因此 descriptor 会把 data voter
 * 映射到 RaftGroup peers，把 learner 映射到 listener 候选集合，并显式保留 witness
 * 副本标识，供后续 witness RPC/fencing 接入。</p>
 */
public final class RegionRaftGroupDescriptor {
  private final RegionMetadata region;
  private final RaftGroup raftGroup;
  private final List<RaftPeer> dataVoters;
  private final List<RaftPeer> learners;
  private final List<String> witnessVoterIds;

  /**
   * 创建 region Raft group 绑定描述。
   *
   * @param region region 元数据
   * @param raftGroup 映射得到的 RaftGroup
   * @param dataVoters data voter peers
   * @param learners learner peers
   * @param witnessVoterIds witness voter 标识集合
   */
  public RegionRaftGroupDescriptor(RegionMetadata region, RaftGroup raftGroup,
      List<RaftPeer> dataVoters, List<RaftPeer> learners,
      List<String> witnessVoterIds) {
    this.region = Objects.requireNonNull(region, "region == null");
    this.raftGroup = Objects.requireNonNull(raftGroup, "raftGroup == null");
    this.dataVoters = immutable(dataVoters);
    this.learners = immutable(learners);
    this.witnessVoterIds = immutableStrings(witnessVoterIds);
  }

  public RegionMetadata getRegion() {
    return region;
  }

  public RaftGroup getRaftGroup() {
    return raftGroup;
  }

  public List<RaftPeer> getDataVoters() {
    return dataVoters;
  }

  public List<RaftPeer> getLearners() {
    return learners;
  }

  public List<String> getWitnessVoterIds() {
    return witnessVoterIds;
  }

  private static List<RaftPeer> immutable(List<RaftPeer> peers) {
    return Collections.unmodifiableList(new ArrayList<>(
        Objects.requireNonNull(peers, "peers == null")));
  }

  private static List<String> immutableStrings(List<String> values) {
    List<String> copy = new ArrayList<>(Objects.requireNonNull(values,
        "values == null"));
    return Collections.unmodifiableList(copy);
  }
}
