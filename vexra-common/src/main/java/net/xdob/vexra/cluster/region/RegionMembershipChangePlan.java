package net.xdob.vexra.cluster.region;

import net.xdob.vexra.protocol.SetConfigurationRequest;

import java.util.Objects;

/**
 * Region 成员变更计划。
 *
 * <p>该计划把当前和目标 region RaftGroup 描述转换成现有 Raft 配置变更参数。
 * data voter 对应 servers，learner 对应 listeners，witness voter 暂由 witness HA 层处理。</p>
 */
public final class RegionMembershipChangePlan {
  private final RegionRaftGroupDescriptor current;
  private final RegionRaftGroupDescriptor target;

  /**
   * 创建成员变更计划。
   *
   * @param current 当前 region RaftGroup 描述
   * @param target 目标 region RaftGroup 描述
   */
  public RegionMembershipChangePlan(RegionRaftGroupDescriptor current,
      RegionRaftGroupDescriptor target) {
    this.current = Objects.requireNonNull(current, "current == null");
    this.target = Objects.requireNonNull(target, "target == null");
    if (!current.getRegion().getRegionId().equals(target.getRegion().getRegionId())) {
      throw new IllegalArgumentException("regionId mismatch");
    }
  }

  public RegionRaftGroupDescriptor getCurrent() {
    return current;
  }

  public RegionRaftGroupDescriptor getTarget() {
    return target;
  }

  /**
   * 转换为 Vexra Raft 配置变更参数。
   *
   * @return SetConfigurationRequest.Arguments
   */
  public SetConfigurationRequest.Arguments toSetConfigurationArguments() {
    return SetConfigurationRequest.Arguments.newBuilder()
        .setServersInCurrentConf(current.getDataVoters())
        .setListenersInCurrentConf(current.getLearners())
        .setServersInNewConf(target.getDataVoters())
        .setListenersInNewConf(target.getLearners())
        .setMode(SetConfigurationRequest.Mode.COMPARE_AND_SET)
        .build();
  }
}
