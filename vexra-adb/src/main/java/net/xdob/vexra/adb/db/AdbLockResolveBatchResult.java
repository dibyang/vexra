package net.xdob.vexra.adb.db;

/**
 * ADB lock resolve 批处理结果。
 *
 * <p>该对象用于后台 worker 或手动恢复命令记录一次扫描/清理的效果。</p>
 */
public final class AdbLockResolveBatchResult {
  private final int scannedLocks;
  private final int rolledBackLocks;

  /**
   * 创建批处理结果。
   *
   * @param scannedLocks 本轮扫描出的过期 lock 数
   * @param rolledBackLocks 本轮成功 rollback 的 lock 数
   */
  public AdbLockResolveBatchResult(int scannedLocks, int rolledBackLocks) {
    if (scannedLocks < 0) {
      throw new IllegalArgumentException("scannedLocks is negative: "
          + scannedLocks);
    }
    if (rolledBackLocks < 0) {
      throw new IllegalArgumentException("rolledBackLocks is negative: "
          + rolledBackLocks);
    }
    if (rolledBackLocks > scannedLocks) {
      throw new IllegalArgumentException("rolledBackLocks exceeds scannedLocks");
    }
    this.scannedLocks = scannedLocks;
    this.rolledBackLocks = rolledBackLocks;
  }

  public int getScannedLocks() {
    return scannedLocks;
  }

  public int getRolledBackLocks() {
    return rolledBackLocks;
  }
}
