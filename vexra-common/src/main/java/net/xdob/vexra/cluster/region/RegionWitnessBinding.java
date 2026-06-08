package net.xdob.vexra.cluster.region;

import net.xdob.vexra.ha.failover.FailoverDecision;
import net.xdob.vexra.ha.failover.FailoverPlanner;
import net.xdob.vexra.ha.quorum.QuorumWriteGate;
import net.xdob.vexra.ha.witness.WitnessState;
import net.xdob.vexra.ha.witness.WitnessStateManager;

import java.io.IOException;
import java.util.Collection;
import java.util.Objects;

/**
 * Region 与 witness HA 能力的运行时绑定入口。
 *
 * <p>该类把 region 元数据接入已实现的 quorum write gate、failover planner 和
 * witness vote state manager。它不负责 RPC 传输，只提供服务端写路径和 witness RPC
 * 可以调用的确定性入口。</p>
 */
public final class RegionWitnessBinding {
  private final QuorumWriteGate writeGate;
  private final FailoverPlanner failoverPlanner;
  private final WitnessStateManager witnessStateManager;

  /**
   * 创建 region witness 绑定入口。
   *
   * @param witnessStateManager witness 状态管理器
   */
  public RegionWitnessBinding(WitnessStateManager witnessStateManager) {
    this(new QuorumWriteGate(), new FailoverPlanner(), witnessStateManager);
  }

  /**
   * 创建 region witness 绑定入口。
   *
   * @param writeGate 多数派写入 gate
   * @param failoverPlanner 故障切换规划器
   * @param witnessStateManager witness 状态管理器
   */
  public RegionWitnessBinding(QuorumWriteGate writeGate,
      FailoverPlanner failoverPlanner,
      WitnessStateManager witnessStateManager) {
    this.writeGate = Objects.requireNonNull(writeGate, "writeGate == null");
    this.failoverPlanner = Objects.requireNonNull(failoverPlanner,
        "failoverPlanner == null");
    this.witnessStateManager = Objects.requireNonNull(witnessStateManager,
        "witnessStateManager == null");
  }

  /**
   * 在 region 写入前执行多数派 fencing。
   *
   * @param region region 元数据
   * @param leaderId 当前 leader 标识
   * @param acknowledgedReplicaIds 已确认或可达副本集合
   * @throws IllegalStateException 当没有可写多数派时抛出
   */
  public void fenceWrite(RegionMetadata region, String leaderId,
      Collection<String> acknowledgedReplicaIds) {
    Objects.requireNonNull(region, "region == null");
    writeGate.requireWritable(region.getReplicaMetadata(), leaderId,
        acknowledgedReplicaIds);
  }

  /**
   * 基于 region 当前可达副本规划故障切换。
   *
   * @param region region 元数据
   * @param reachableReplicaIds 可达副本集合
   * @return 故障切换规划结果
   */
  public FailoverDecision planFailover(RegionMetadata region,
      Collection<String> reachableReplicaIds) {
    Objects.requireNonNull(region, "region == null");
    return failoverPlanner.plan(region.getReplicaMetadata(),
        reachableReplicaIds);
  }

  /**
   * 给指定 region 的 witness 状态授票。
   *
   * @param region region 元数据
   * @param candidateId 候选 data 节点标识
   * @param term 请求投票任期
   * @return 授票后的 witness 状态
   * @throws IOException 当状态持久化失败时抛出
   */
  public WitnessState grantWitnessVote(RegionMetadata region,
      String candidateId, long term) throws IOException {
    Objects.requireNonNull(region, "region == null");
    return witnessStateManager.grantVote(
        region.getReplicaMetadata().getVirtualNodeId(), candidateId, term);
  }
}
