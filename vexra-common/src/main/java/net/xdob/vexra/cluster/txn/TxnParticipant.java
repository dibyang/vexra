package net.xdob.vexra.cluster.txn;

/**
 * 分布式事务参与 region。
 */
public final class TxnParticipant {
  private final String regionId;
  private final boolean primary;

  /**
   * 创建事务参与 region。
   *
   * @param regionId region 标识
   * @param primary 是否为 primary region
   */
  public TxnParticipant(String regionId, boolean primary) {
    if (regionId == null || regionId.trim().isEmpty()) {
      throw new IllegalArgumentException("regionId is empty");
    }
    this.regionId = regionId.trim();
    this.primary = primary;
  }

  public String getRegionId() {
    return regionId;
  }

  public boolean isPrimary() {
    return primary;
  }
}
