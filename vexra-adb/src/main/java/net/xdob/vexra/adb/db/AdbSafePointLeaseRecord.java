package net.xdob.vexra.adb.db;

/**
 * ADB safe point lease 持久化记录。
 *
 * <p>该值对象表示 META CF 中保存的全局 safe point、当前 lease owner 和 lease
 * 到期时间。它不负责抢占语义，抢占、续租和释放由
 * {@link AdbSafePointLeaseStore} 统一处理。</p>
 */
public final class AdbSafePointLeaseRecord {
  private final long safePoint;
  private final String ownerId;
  private final long leaseUntilMillis;

  /**
   * 创建 safe point lease 记录。
   *
   * @param safePoint 当前持久化 safe point
   * @param ownerId 当前 lease owner，空字符串表示没有持有者
   * @param leaseUntilMillis lease 到期时间戳，0 表示未持有
   */
  public AdbSafePointLeaseRecord(long safePoint, String ownerId,
      long leaseUntilMillis) {
    if (safePoint < 0) {
      throw new IllegalArgumentException("safePoint is negative: "
          + safePoint);
    }
    if (leaseUntilMillis < 0) {
      throw new IllegalArgumentException("leaseUntilMillis is negative: "
          + leaseUntilMillis);
    }
    this.safePoint = safePoint;
    this.ownerId = ownerId == null ? "" : ownerId.trim();
    this.leaseUntilMillis = leaseUntilMillis;
  }

  public long getSafePoint() {
    return safePoint;
  }

  public String getOwnerId() {
    return ownerId;
  }

  public long getLeaseUntilMillis() {
    return leaseUntilMillis;
  }

  /**
   * 判断指定时间点租约是否仍有效。
   *
   * @param nowMillis 当前时间戳
   * @return 有 owner 且未到期时返回 true
   */
  public boolean isLeaseActive(long nowMillis) {
    if (nowMillis < 0) {
      throw new IllegalArgumentException("nowMillis is negative: "
          + nowMillis);
    }
    return !ownerId.isEmpty() && leaseUntilMillis > nowMillis;
  }

  /**
   * 判断指定 owner 是否持有未过期租约。
   *
   * @param ownerId owner 标识
   * @param nowMillis 当前时间戳
   * @return 指定 owner 持有且租约未过期时返回 true
   */
  public boolean isHeldBy(String ownerId, long nowMillis) {
    return isLeaseActive(nowMillis) && this.ownerId.equals(normalize(ownerId));
  }

  private static String normalize(String value) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException("ownerId is empty");
    }
    return value.trim();
  }
}
