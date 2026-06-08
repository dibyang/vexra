package net.xdob.vexra.ha;

import java.util.Objects;

/**
 * 虚节点副本描述。
 *
 * <p>该对象只保存副本标识和 HA 副本角色，可用于管理面、系统表或后续 witness
 * 仲裁逻辑展示 data/witness/learner 拓扑。</p>
 */
public final class VirtualNodeReplica {
  private final String replicaId;
  private final ReplicaRole role;

  /**
   * 创建虚节点副本描述。
   *
   * @param replicaId 副本节点标识，不能为空
   * @param role HA 副本角色
   */
  public VirtualNodeReplica(String replicaId, ReplicaRole role) {
    this.replicaId = normalizeReplicaId(replicaId);
    this.role = Objects.requireNonNull(role, "role == null");
  }

  public String getReplicaId() {
    return replicaId;
  }

  public ReplicaRole getRole() {
    return role;
  }

  /**
   * 判断该副本是否有资格成为 leader。
   *
   * @return 数据投票副本返回 true，witness 和 learner 返回 false
   */
  public boolean canLead() {
    return role.canLead();
  }

  private static String normalizeReplicaId(String replicaId) {
    if (replicaId == null || replicaId.trim().isEmpty()) {
      throw new IllegalArgumentException("replicaId is empty");
    }
    return replicaId.trim();
  }

  @Override
  public String toString() {
    return replicaId + ":" + role;
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof VirtualNodeReplica)) {
      return false;
    }
    VirtualNodeReplica that = (VirtualNodeReplica) other;
    return replicaId.equals(that.replicaId) && role == that.role;
  }

  @Override
  public int hashCode() {
    return Objects.hash(replicaId, role);
  }
}
