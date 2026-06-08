package net.xdob.vexra.ha.quorum;

/**
 * 多数派写入 gate 判定结果。
 *
 * <p>该对象用于把“是否允许写入”和拒绝原因一起返回给调用方，后续服务端可以把
 * 拒绝原因映射成 not-ready、not-replicated 或 unavailable 等响应。</p>
 */
public final class WriteGateDecision {
  private final boolean allowed;
  private final String reason;
  private final int requiredQuorum;
  private final int acknowledgedVoters;

  private WriteGateDecision(boolean allowed, String reason, int requiredQuorum,
      int acknowledgedVoters) {
    this.allowed = allowed;
    this.reason = reason;
    this.requiredQuorum = requiredQuorum;
    this.acknowledgedVoters = acknowledgedVoters;
  }

  /**
   * 创建允许写入的判定结果。
   *
   * @param requiredQuorum 所需多数派数量
   * @param acknowledgedVoters 已确认投票副本数量
   * @return 允许写入结果
   */
  public static WriteGateDecision allow(int requiredQuorum,
      int acknowledgedVoters) {
    return new WriteGateDecision(true, "", requiredQuorum, acknowledgedVoters);
  }

  /**
   * 创建拒绝写入的判定结果。
   *
   * @param reason 拒绝原因
   * @param requiredQuorum 所需多数派数量
   * @param acknowledgedVoters 已确认投票副本数量
   * @return 拒绝写入结果
   */
  public static WriteGateDecision deny(String reason, int requiredQuorum,
      int acknowledgedVoters) {
    return new WriteGateDecision(false, reason, requiredQuorum,
        acknowledgedVoters);
  }

  public boolean isAllowed() {
    return allowed;
  }

  public String getReason() {
    return reason;
  }

  public int getRequiredQuorum() {
    return requiredQuorum;
  }

  public int getAcknowledgedVoters() {
    return acknowledgedVoters;
  }
}
