package net.xdob.vexra.ha;

/**
 * 虚节点副本在 HA 拓扑中的职责。
 *
 * <p>该角色不同于 Raft 运行时的 {@code LEADER/FOLLOWER/CANDIDATE}。
 * 它描述副本是否保存业务数据、是否参与投票，以及是否允许成为业务 leader。</p>
 */
public enum ReplicaRole {
  /** 保存业务数据、参与投票，并允许成为 leader 的普通数据副本。 */
  DATA_VOTER(true, true, true),

  /** 不保存业务数据，只参与投票和仲裁，不能成为 leader。 */
  WITNESS_VOTER(false, true, false),

  /** 保存业务数据但不参与投票，常用于追赶、扩容或迁移。 */
  LEARNER(true, false, false);

  private final boolean storesData;
  private final boolean voter;
  private final boolean leaderEligible;

  ReplicaRole(boolean storesData, boolean voter, boolean leaderEligible) {
    this.storesData = storesData;
    this.voter = voter;
    this.leaderEligible = leaderEligible;
  }

  public boolean storesData() {
    return storesData;
  }

  public boolean isVoter() {
    return voter;
  }

  public boolean canLead() {
    return leaderEligible;
  }
}
