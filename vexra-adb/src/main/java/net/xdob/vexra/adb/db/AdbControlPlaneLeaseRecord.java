package net.xdob.vexra.adb.db;

/**
 * ADB 控制面通用 lease 记录。
 *
 * <p>该记录对应 GA-03 规划中的 `adb_cp_lease`，用于表达 TSO owner、后台任务
 * owner 或后续控制面 leader lease。它只负责本地持久化模型，真正的跨进程 fencing
 * 和复制由后续控制面服务化实现。</p>
 */
public final class AdbControlPlaneLeaseRecord {
  private final String leaseName;
  private final String owner;
  private final long epoch;
  private final long expireAtMillis;
  private final long fencingToken;

  /**
   * 创建控制面 lease 记录。
   *
   * @param leaseName lease 名称
   * @param owner 当前 owner
   * @param epoch lease epoch
   * @param expireAtMillis 过期时间戳
   * @param fencingToken fencing token
   */
  public AdbControlPlaneLeaseRecord(String leaseName, String owner,
      long epoch, long expireAtMillis, long fencingToken) {
    this.leaseName = normalize(leaseName, "leaseName");
    this.owner = normalize(owner, "owner");
    this.epoch = nonNegative(epoch, "epoch");
    this.expireAtMillis = nonNegative(expireAtMillis, "expireAtMillis");
    this.fencingToken = nonNegative(fencingToken, "fencingToken");
  }

  public String getLeaseName() {
    return leaseName;
  }

  public String getOwner() {
    return owner;
  }

  public long getEpoch() {
    return epoch;
  }

  public long getExpireAtMillis() {
    return expireAtMillis;
  }

  public long getFencingToken() {
    return fencingToken;
  }

  /**
   * 判断 lease 在指定时间是否仍有效。
   *
   * @param nowMillis 当前时间戳
   * @return 未过期时返回 true
   */
  public boolean isActive(long nowMillis) {
    return expireAtMillis > nonNegative(nowMillis, "nowMillis");
  }

  private static String normalize(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return value.trim();
  }

  private static long nonNegative(long value, String fieldName) {
    if (value < 0) {
      throw new IllegalArgumentException(fieldName + " is negative: "
          + value);
    }
    return value;
  }
}
