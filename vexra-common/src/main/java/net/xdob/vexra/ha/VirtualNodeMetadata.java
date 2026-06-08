package net.xdob.vexra.ha;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 虚节点 HA 元数据快照。
 *
 * <p>该对象用于描述一个虚节点/region/shard 的副本集合、leader、epoch、term
 * 和提交进度。它是不可变值对象，后续可以作为系统表展示、调度器路由或 witness
 * 仲裁状态的输入。</p>
 */
public final class VirtualNodeMetadata {
  private final String virtualNodeId;
  private final long epoch;
  private final String leaderId;
  private final List<VirtualNodeReplica> replicas;
  private final long commitIndex;
  private final long term;
  private final long leaseUntilMillis;

  /**
   * 创建虚节点 HA 元数据快照。
   *
   * @param virtualNodeId 虚节点、region 或 shard 标识
   * @param epoch 元数据版本
   * @param leaderId 当前 leader 副本标识，可为空
   * @param replicas 副本列表
   * @param commitIndex 已提交日志位置
   * @param term 当前任期
   * @param leaseUntilMillis 可选 leader lease 过期时间，0 表示未启用
   */
  public VirtualNodeMetadata(String virtualNodeId, long epoch, String leaderId,
      List<VirtualNodeReplica> replicas, long commitIndex, long term,
      long leaseUntilMillis) {
    this.virtualNodeId = normalizeId(virtualNodeId, "virtualNodeId");
    this.epoch = nonNegative(epoch, "epoch");
    this.leaderId = normalizeOptionalId(leaderId);
    this.replicas = immutableReplicas(replicas);
    this.commitIndex = nonNegative(commitIndex, "commitIndex");
    this.term = nonNegative(term, "term");
    this.leaseUntilMillis = nonNegative(leaseUntilMillis, "leaseUntilMillis");
    validateLeader();
  }

  public String getVirtualNodeId() {
    return virtualNodeId;
  }

  public long getEpoch() {
    return epoch;
  }

  public String getLeaderId() {
    return leaderId;
  }

  public List<VirtualNodeReplica> getReplicas() {
    return replicas;
  }

  public long getCommitIndex() {
    return commitIndex;
  }

  public long getTerm() {
    return term;
  }

  public long getLeaseUntilMillis() {
    return leaseUntilMillis;
  }

  /**
   * 查找当前 leader 对应的副本描述。
   *
   * @return 未设置 leader 时返回空
   */
  public Optional<VirtualNodeReplica> getLeaderReplica() {
    if (leaderId.isEmpty()) {
      return Optional.empty();
    }
    for (VirtualNodeReplica replica : replicas) {
      if (leaderId.equals(replica.getReplicaId())) {
        return Optional.of(replica);
      }
    }
    return Optional.empty();
  }

  /**
   * 统计参与投票的副本数。
   *
   * @return data voter 与 witness voter 数量之和
   */
  public int voterCount() {
    int voters = 0;
    for (VirtualNodeReplica replica : replicas) {
      if (replica.getRole().isVoter()) {
        voters++;
      }
    }
    return voters;
  }

  /**
   * 判断该虚节点是否包含 witness 投票副本。
   *
   * @return 存在 witness voter 时返回 true
   */
  public boolean hasWitness() {
    for (VirtualNodeReplica replica : replicas) {
      if (replica.getRole() == ReplicaRole.WITNESS_VOTER) {
        return true;
      }
    }
    return false;
  }

  /**
   * 判断该虚节点是否满足 2 data + 1 witness 的基础拓扑形态。
   *
   * @return 恰好两个 data voter 且至少一个 witness voter 时返回 true
   */
  public boolean isTwoDataOneWitnessTopology() {
    int dataVoters = 0;
    int witnesses = 0;
    for (VirtualNodeReplica replica : replicas) {
      if (replica.getRole() == ReplicaRole.DATA_VOTER) {
        dataVoters++;
      } else if (replica.getRole() == ReplicaRole.WITNESS_VOTER) {
        witnesses++;
      }
    }
    return dataVoters == 2 && witnesses >= 1;
  }

  /**
   * 创建更新 leader 后的新元数据。
   *
   * @param newLeaderId 新 leader 副本标识
   * @param newEpoch 新 epoch，必须不小于当前 epoch
   * @return 更新 leader 后的元数据快照
   */
  public VirtualNodeMetadata withLeader(String newLeaderId, long newEpoch) {
    if (newEpoch < epoch) {
      throw new IllegalArgumentException(
          "epoch regression from " + epoch + " to " + newEpoch);
    }
    return new VirtualNodeMetadata(virtualNodeId, newEpoch, newLeaderId,
        replicas, commitIndex, term, leaseUntilMillis);
  }

  private void validateLeader() {
    Optional<VirtualNodeReplica> leader = getLeaderReplica();
    if (!leaderId.isEmpty() && !leader.isPresent()) {
      throw new IllegalArgumentException("leaderId is not in replicas: " + leaderId);
    }
    if (leader.isPresent() && !leader.get().canLead()) {
      throw new IllegalArgumentException(
          "leader must be a DATA_VOTER replica: " + leaderId);
    }
  }

  private static List<VirtualNodeReplica> immutableReplicas(
      List<VirtualNodeReplica> replicas) {
    Objects.requireNonNull(replicas, "replicas == null");
    if (replicas.isEmpty()) {
      throw new IllegalArgumentException("replicas is empty");
    }
    List<VirtualNodeReplica> copy = new ArrayList<>(replicas);
    Set<String> ids = new HashSet<>();
    for (VirtualNodeReplica replica : copy) {
      Objects.requireNonNull(replica, "replica == null");
      if (!ids.add(replica.getReplicaId())) {
        throw new IllegalArgumentException(
            "duplicate replicaId: " + replica.getReplicaId());
      }
    }
    return Collections.unmodifiableList(copy);
  }

  private static String normalizeId(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return value.trim();
  }

  private static String normalizeOptionalId(String value) {
    return value == null ? "" : value.trim();
  }

  private static long nonNegative(long value, String fieldName) {
    if (value < 0) {
      throw new IllegalArgumentException(fieldName + " is negative: " + value);
    }
    return value;
  }
}
