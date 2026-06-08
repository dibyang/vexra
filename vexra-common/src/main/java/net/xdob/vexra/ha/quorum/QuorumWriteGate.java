package net.xdob.vexra.ha.quorum;

import net.xdob.vexra.ha.VirtualNodeMetadata;
import net.xdob.vexra.ha.VirtualNodeReplica;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 多数派写入 gate。
 *
 * <p>该类不提交日志，也不访问网络，只根据虚节点元数据和已确认副本集合判定当前写入
 * 是否满足多数派。witness voter 会参与多数派计数，learner 不参与投票计数。</p>
 */
public final class QuorumWriteGate {
  /**
   * 判定当前写入是否满足多数派要求。
   *
   * @param metadata 虚节点 HA 元数据
   * @param leaderId 当前处理写入的 leader 副本标识
   * @param acknowledgedReplicaIds 已确认或可达的副本标识集合，通常包含 leader 自身
   * @return 写入 gate 判定结果
   */
  public WriteGateDecision evaluate(VirtualNodeMetadata metadata,
      String leaderId, Collection<String> acknowledgedReplicaIds) {
    Objects.requireNonNull(metadata, "metadata == null");
    Set<String> acknowledged = normalizeAcknowledgements(acknowledgedReplicaIds);
    int required = quorum(metadata.voterCount());

    if (metadata.voterCount() == 0) {
      return WriteGateDecision.deny("no voter replicas", required, 0);
    }
    if (leaderId == null || leaderId.trim().isEmpty()) {
      return WriteGateDecision.deny("leader is empty", required, 0);
    }
    String normalizedLeader = leaderId.trim();
    if (!normalizedLeader.equals(metadata.getLeaderId())) {
      return WriteGateDecision.deny("leader does not match metadata",
          required, countAcknowledgedVoters(metadata, acknowledged));
    }
    if (!metadata.getLeaderReplica().isPresent()
        || !metadata.getLeaderReplica().get().canLead()) {
      return WriteGateDecision.deny("leader is not writable data voter",
          required, countAcknowledgedVoters(metadata, acknowledged));
    }

    int acknowledgedVoters = countAcknowledgedVoters(metadata, acknowledged);
    if (acknowledgedVoters < required) {
      return WriteGateDecision.deny("quorum is not satisfied",
          required, acknowledgedVoters);
    }
    return WriteGateDecision.allow(required, acknowledgedVoters);
  }

  /**
   * 计算多数派数量。
   *
   * @param voterCount 投票副本数量
   * @return 多数派阈值
   */
  public int quorum(int voterCount) {
    if (voterCount < 0) {
      throw new IllegalArgumentException("voterCount is negative: " + voterCount);
    }
    return voterCount / 2 + 1;
  }

  /**
   * 判定失败时直接抛出异常，便于写入路径快速中断。
   *
   * @param metadata 虚节点 HA 元数据
   * @param leaderId 当前 leader 标识
   * @param acknowledgedReplicaIds 已确认或可达的副本标识集合
   * @throws IllegalStateException 当写入不满足 gate 时抛出
   */
  public void requireWritable(VirtualNodeMetadata metadata, String leaderId,
      Collection<String> acknowledgedReplicaIds) {
    WriteGateDecision decision = evaluate(metadata, leaderId,
        acknowledgedReplicaIds);
    if (!decision.isAllowed()) {
      throw new IllegalStateException(decision.getReason());
    }
  }

  private int countAcknowledgedVoters(VirtualNodeMetadata metadata,
      Set<String> acknowledged) {
    int count = 0;
    for (VirtualNodeReplica replica : metadata.getReplicas()) {
      if (replica.getRole().isVoter()
          && acknowledged.contains(replica.getReplicaId())) {
        count++;
      }
    }
    return count;
  }

  private static Set<String> normalizeAcknowledgements(
      Collection<String> acknowledgedReplicaIds) {
    Objects.requireNonNull(acknowledgedReplicaIds,
        "acknowledgedReplicaIds == null");
    Set<String> acknowledged = new HashSet<>();
    for (String id : acknowledgedReplicaIds) {
      if (id != null && !id.trim().isEmpty()) {
        acknowledged.add(id.trim());
      }
    }
    return acknowledged;
  }
}
