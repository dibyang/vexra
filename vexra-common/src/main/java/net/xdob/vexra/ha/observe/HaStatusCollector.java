package net.xdob.vexra.ha.observe;

import net.xdob.vexra.ha.HaConfig;
import net.xdob.vexra.ha.VirtualNodeMetadata;
import net.xdob.vexra.ha.VirtualNodeReplica;
import net.xdob.vexra.ha.failover.FailoverDecision;
import net.xdob.vexra.ha.failover.FailoverPlanner;
import net.xdob.vexra.ha.quorum.QuorumWriteGate;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * HA 状态采集器。
 *
 * <p>采集器把配置、虚节点元数据和当前可达副本集合转换成观测快照。它不依赖具体
 * metrics registry，因此可以被系统表、命令行或服务端 metrics 适配器复用。</p>
 */
public final class HaStatusCollector {
  private final FailoverPlanner failoverPlanner = new FailoverPlanner();
  private final QuorumWriteGate writeGate = new QuorumWriteGate();

  /**
   * 采集当前 HA 状态。
   *
   * @param config HA 配置
   * @param metadata 虚节点元数据
   * @param reachableReplicaIds 当前可达副本标识集合
   * @return HA 观测快照
   */
  public HaStatusSnapshot collect(HaConfig config, VirtualNodeMetadata metadata,
      Collection<String> reachableReplicaIds) {
    Objects.requireNonNull(config, "config == null");
    Objects.requireNonNull(metadata, "metadata == null");
    Set<String> reachable = normalize(reachableReplicaIds);
    FailoverDecision decision = failoverPlanner.plan(metadata, reachable);
    return new HaStatusSnapshot(config.getMode(), metadata.getVirtualNodeId(),
        decision.getLeaderId(), decision.getMetadata().getEpoch(),
        decision.getMetadata().getTerm(), metadata.voterCount(),
        reachableVoters(metadata, reachable), writeGate.quorum(metadata.voterCount()),
        metadata.hasWitness(), decision.isWritable(), decision.getStatus());
  }

  private static int reachableVoters(VirtualNodeMetadata metadata,
      Set<String> reachable) {
    int count = 0;
    for (VirtualNodeReplica replica : metadata.getReplicas()) {
      if (replica.getRole().isVoter()
          && reachable.contains(replica.getReplicaId())) {
        count++;
      }
    }
    return count;
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
