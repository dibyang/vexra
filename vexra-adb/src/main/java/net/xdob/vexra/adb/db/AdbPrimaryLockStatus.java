package net.xdob.vexra.adb.db;

/**
 * ADB primary lock 状态。
 *
 * <p>该对象是 lock resolver 与 primary 状态查询实现之间的边界。当前 resolver
 * 只消费 committed/unknown，后续跨 region 查询可以在不改 resolver 主流程的情况下扩展
 * rolled-back、timeout 或远端错误语义。</p>
 */
public final class AdbPrimaryLockStatus {
  private static final AdbPrimaryLockStatus UNKNOWN =
      new AdbPrimaryLockStatus(false, 0);

  private final boolean committed;
  private final long commitTs;

  private AdbPrimaryLockStatus(boolean committed, long commitTs) {
    if (committed && commitTs <= 0) {
      throw new IllegalArgumentException("commitTs must be positive");
    }
    this.committed = committed;
    this.commitTs = commitTs;
  }

  /**
   * 创建 primary 已提交状态。
   *
   * @param commitTs primary commit timestamp
   * @return committed 状态
   */
  public static AdbPrimaryLockStatus committed(long commitTs) {
    return new AdbPrimaryLockStatus(true, commitTs);
  }

  /**
   * 创建未知状态。
   *
   * @return unknown 状态
   */
  public static AdbPrimaryLockStatus unknown() {
    return UNKNOWN;
  }

  public boolean isCommitted() {
    return committed;
  }

  public long getCommitTs() {
    return commitTs;
  }
}
