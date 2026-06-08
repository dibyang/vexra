package net.xdob.vexra.ha.failover;

import net.xdob.vexra.ha.VirtualNodeMetadata;
import net.xdob.vexra.ha.VirtualNodeReplica;
import net.xdob.vexra.ha.quorum.QuorumWriteGate;
import net.xdob.vexra.ha.quorum.WriteGateDecision;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 基于多数派的故障切换规划器。
 *
 * <p>规划器只做确定性决策，不直接发起 RPC 或写磁盘。它要求新 leader 必须是可达的
 * data voter，并且可达投票副本必须满足多数派；witness 只能参与仲裁，不能成为 leader。</p>
 */
public final class FailoverPlanner {
  private final QuorumWriteGate writeGate = new QuorumWriteGate();

  /**
   * 根据可达副本集合规划故障切换。
   *
   * @param metadata 当前虚节点元数据
   * @param reachableReplicaIds 可达副本标识集合
   * @return 故障切换规划结果
   */
  public FailoverDecision plan(VirtualNodeMetadata metadata,
      Collection<String> reachableReplicaIds) {
    Objects.requireNonNull(metadata, "metadata == null");
    Set<String> reachable = normalize(reachableReplicaIds);

    if (metadata.getLeaderReplica().isPresent()
        && reachable.contains(metadata.getLeaderId())) {
      WriteGateDecision decision = writeGate.evaluate(metadata,
          metadata.getLeaderId(), reachable);
      if (decision.isAllowed()) {
        return new FailoverDecision(FailoverStatus.KEEP_LEADER, true,
            metadata.getLeaderId(), "", metadata);
      }
    }

    String candidate = firstReachableDataVoter(metadata, reachable);
    if (candidate.isEmpty()) {
      return new FailoverDecision(FailoverStatus.UNAVAILABLE, false, "",
          "no reachable data voter can lead", metadata);
    }

    VirtualNodeMetadata promoted = metadata.withLeader(candidate,
        metadata.getEpoch() + 1);
    WriteGateDecision promotedDecision = writeGate.evaluate(promoted,
        candidate, reachable);
    if (promotedDecision.isAllowed()) {
      return new FailoverDecision(FailoverStatus.PROMOTE_DATA_LEADER, true,
          candidate, "", promoted);
    }
    return new FailoverDecision(FailoverStatus.DEGRADED_READONLY, false,
        candidate, promotedDecision.getReason(), promoted);
  }

  private static String firstReachableDataVoter(VirtualNodeMetadata metadata,
      Set<String> reachable) {
    for (VirtualNodeReplica replica : metadata.getReplicas()) {
      if (replica.canLead() && reachable.contains(replica.getReplicaId())) {
        return replica.getReplicaId();
      }
    }
    return "";
  }

  private static Set<String> normalize(Collection<String> replicaIds) {
    Objects.requireNonNull(replicaIds, "replicaIds == null");
    Set<String> result = new HashSet<>();
    for (String replicaId : replicaIds) {
      if (replicaId != null && !replicaId.trim().isEmpty()) {
        result.add(replicaId.trim());
      }
    }
    return result;
  }
}
