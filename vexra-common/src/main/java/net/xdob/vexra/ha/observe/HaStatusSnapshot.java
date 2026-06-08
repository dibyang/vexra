package net.xdob.vexra.ha.observe;

import net.xdob.vexra.ha.HaMode;
import net.xdob.vexra.ha.failover.FailoverStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * HA 观测快照。
 *
 * <p>该对象用于把 HA 模式、虚节点元数据、多数派和故障切换结果整理成稳定字段。
 * 后续系统表、metrics、日志或管理接口都可以复用这些字段。</p>
 */
public final class HaStatusSnapshot {
  private final HaMode mode;
  private final String virtualNodeId;
  private final String leaderId;
  private final long epoch;
  private final long term;
  private final int voterCount;
  private final int reachableVoters;
  private final int requiredQuorum;
  private final boolean witnessPresent;
  private final boolean writable;
  private final FailoverStatus failoverStatus;

  /**
   * 创建 HA 观测快照。
   *
   * @param mode HA 模式
   * @param virtualNodeId 虚节点标识
   * @param leaderId 当前 leader 标识
   * @param epoch 元数据 epoch
   * @param term 当前 term
   * @param voterCount 投票副本数
   * @param reachableVoters 可达投票副本数
   * @param requiredQuorum 多数派阈值
   * @param witnessPresent 是否存在 witness
   * @param writable 当前是否可写
   * @param failoverStatus 故障切换状态
   */
  public HaStatusSnapshot(HaMode mode, String virtualNodeId, String leaderId,
      long epoch, long term, int voterCount, int reachableVoters,
      int requiredQuorum, boolean witnessPresent, boolean writable,
      FailoverStatus failoverStatus) {
    this.mode = Objects.requireNonNull(mode, "mode == null");
    this.virtualNodeId = normalize(virtualNodeId);
    this.leaderId = normalize(leaderId);
    this.epoch = epoch;
    this.term = term;
    this.voterCount = voterCount;
    this.reachableVoters = reachableVoters;
    this.requiredQuorum = requiredQuorum;
    this.witnessPresent = witnessPresent;
    this.writable = writable;
    this.failoverStatus = Objects.requireNonNull(failoverStatus,
        "failoverStatus == null");
  }

  public HaMode getMode() {
    return mode;
  }

  public String getVirtualNodeId() {
    return virtualNodeId;
  }

  public String getLeaderId() {
    return leaderId;
  }

  public long getEpoch() {
    return epoch;
  }

  public long getTerm() {
    return term;
  }

  public int getVoterCount() {
    return voterCount;
  }

  public int getReachableVoters() {
    return reachableVoters;
  }

  public int getRequiredQuorum() {
    return requiredQuorum;
  }

  public boolean isWitnessPresent() {
    return witnessPresent;
  }

  public boolean isWritable() {
    return writable;
  }

  public FailoverStatus getFailoverStatus() {
    return failoverStatus;
  }

  /**
   * 转换为系统表友好的字符串行。
   *
   * @return 字段名到字符串值的有序映射
   */
  public Map<String, String> toSystemTableRow() {
    Map<String, String> row = new LinkedHashMap<>();
    row.put("mode", mode.name());
    row.put("virtual_node_id", virtualNodeId);
    row.put("leader_id", leaderId);
    row.put("epoch", Long.toString(epoch));
    row.put("term", Long.toString(term));
    row.put("voter_count", Integer.toString(voterCount));
    row.put("reachable_voters", Integer.toString(reachableVoters));
    row.put("required_quorum", Integer.toString(requiredQuorum));
    row.put("witness_present", Boolean.toString(witnessPresent));
    row.put("writable", Boolean.toString(writable));
    row.put("failover_status", failoverStatus.name());
    return row;
  }

  /**
   * 转换为 metrics 友好的数值字段。
   *
   * @return 指标名到数值的有序映射
   */
  public Map<String, Number> toMetrics() {
    Map<String, Number> metrics = new LinkedHashMap<>();
    metrics.put("vexra_ha_voter_count", voterCount);
    metrics.put("vexra_ha_reachable_voters", reachableVoters);
    metrics.put("vexra_ha_required_quorum", requiredQuorum);
    metrics.put("vexra_ha_witness_present", witnessPresent ? 1 : 0);
    metrics.put("vexra_ha_writable", writable ? 1 : 0);
    metrics.put("vexra_ha_epoch", epoch);
    metrics.put("vexra_ha_term", term);
    return metrics;
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim();
  }
}
