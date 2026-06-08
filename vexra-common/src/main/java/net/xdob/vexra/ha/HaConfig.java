package net.xdob.vexra.ha;

import java.util.Objects;

/**
 * HA 配置快照和配置层拓扑校验。
 *
 * <p>该类不访问网络、磁盘或 Raft 状态，只负责把 {@code raft.ha.*} 属性收敛成
 * 一个不可变对象，并在服务端启动或集群配置变更前拒绝不安全拓扑。</p>
 */
public final class HaConfig {
  private final HaMode mode;
  private final HaNodeRole nodeRole;
  private final String replicaId;
  private final String witnessAddress;
  private final boolean sharedStorageEnabled;
  private final boolean quorumWriteRequired;

  /**
   * 创建 HA 配置快照。
   *
   * @param mode 部署模式
   * @param nodeRole 当前节点角色
   * @param replicaId 当前副本标识
   * @param witnessAddress witness 访问地址
   * @param sharedStorageEnabled 是否显式启用共享存储
   * @param quorumWriteRequired 是否要求写入必须满足多数派
   */
  public HaConfig(HaMode mode, HaNodeRole nodeRole, String replicaId,
      String witnessAddress, boolean sharedStorageEnabled,
      boolean quorumWriteRequired) {
    this.mode = Objects.requireNonNull(mode, "mode == null");
    this.nodeRole = Objects.requireNonNull(nodeRole, "nodeRole == null");
    this.replicaId = normalize(replicaId);
    this.witnessAddress = normalize(witnessAddress);
    this.sharedStorageEnabled = sharedStorageEnabled;
    this.quorumWriteRequired = quorumWriteRequired;
  }

  public HaMode getMode() {
    return mode;
  }

  public HaNodeRole getNodeRole() {
    return nodeRole;
  }

  public String getReplicaId() {
    return replicaId;
  }

  public String getWitnessAddress() {
    return witnessAddress;
  }

  public boolean isSharedStorageEnabled() {
    return sharedStorageEnabled;
  }

  public boolean isQuorumWriteRequired() {
    return quorumWriteRequired;
  }

  /**
   * 校验单个节点的 HA 配置是否自洽。
   *
   * @throws IllegalArgumentException 当模式、角色和必要开关不匹配时抛出
   */
  public void validate() {
    switch (mode) {
      case SINGLE:
        if (sharedStorageEnabled) {
          throw new IllegalArgumentException(
              "raft.ha.shared-storage.enabled requires SHARED_STORAGE mode");
        }
        return;
      case WITNESS:
        validateWitnessMode();
        return;
      case SHARED_STORAGE:
        if (!sharedStorageEnabled) {
          throw new IllegalArgumentException(
              "SHARED_STORAGE mode requires raft.ha.shared-storage.enabled=true");
        }
        return;
      default:
        throw new IllegalArgumentException("Unsupported HA mode: " + mode);
    }
  }

  /**
   * 校验集群拓扑是否允许自动强一致写入。
   *
   * @param dataNodeCount 数据节点数量
   * @param witnessNodeCount witness 节点数量
   * @param automaticFailover 是否计划启用自动故障切换或自动写入接管
   * @throws IllegalArgumentException 当拓扑无法安全提供自动强一致写入时抛出
   */
  public void validateTopology(int dataNodeCount, int witnessNodeCount,
      boolean automaticFailover) {
    validate();
    if (dataNodeCount < 0 || witnessNodeCount < 0) {
      throw new IllegalArgumentException("node counts must be non-negative");
    }
    if (dataNodeCount == 2 && witnessNodeCount == 0
        && automaticFailover && mode != HaMode.SHARED_STORAGE) {
      throw new IllegalArgumentException(
          "Pure two-data-node automatic writes require witness or explicit shared storage");
    }
    if (mode == HaMode.WITNESS) {
      if (dataNodeCount < 2) {
        throw new IllegalArgumentException("WITNESS mode requires at least two data nodes");
      }
      if (witnessNodeCount < 1) {
        throw new IllegalArgumentException("WITNESS mode requires at least one witness node");
      }
    }
  }

  private void validateWitnessMode() {
    if (!quorumWriteRequired) {
      throw new IllegalArgumentException(
          "WITNESS mode requires raft.ha.quorum.write-required=true");
    }
    if (sharedStorageEnabled) {
      throw new IllegalArgumentException(
          "WITNESS mode must not enable shared storage");
    }
    if (nodeRole == HaNodeRole.DATA && witnessAddress.isEmpty()) {
      throw new IllegalArgumentException(
          "DATA node in WITNESS mode requires raft.ha.witness.address");
    }
    if (nodeRole == HaNodeRole.DATA && replicaId.isEmpty()) {
      throw new IllegalArgumentException(
          "DATA node in WITNESS mode requires raft.ha.replica.id");
    }
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim();
  }
}
