package net.xdob.vexra.ha.failover;

import net.xdob.vexra.ha.VirtualNodeMetadata;

import java.util.Objects;

/**
 * 故障切换规划结果。
 *
 * <p>该对象描述规划器是否允许继续写入、是否需要切主，以及切主后的新元数据快照。</p>
 */
public final class FailoverDecision {
  private final FailoverStatus status;
  private final boolean writable;
  private final String leaderId;
  private final String reason;
  private final VirtualNodeMetadata metadata;

  /**
   * 创建故障切换规划结果。
   *
   * @param status 规划状态
   * @param writable 是否可写
   * @param leaderId 当前或新 leader
   * @param reason 说明或拒绝原因
   * @param metadata 对应元数据快照
   */
  public FailoverDecision(FailoverStatus status, boolean writable,
      String leaderId, String reason, VirtualNodeMetadata metadata) {
    this.status = Objects.requireNonNull(status, "status == null");
    this.writable = writable;
    this.leaderId = leaderId == null ? "" : leaderId.trim();
    this.reason = reason == null ? "" : reason.trim();
    this.metadata = Objects.requireNonNull(metadata, "metadata == null");
  }

  public FailoverStatus getStatus() {
    return status;
  }

  public boolean isWritable() {
    return writable;
  }

  public String getLeaderId() {
    return leaderId;
  }

  public String getReason() {
    return reason;
  }

  public VirtualNodeMetadata getMetadata() {
    return metadata;
  }
}
