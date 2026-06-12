package net.xdob.vexra.adb.db;

/**
 * ADB lock resolve 批处理结果。
 *
 * <p>该对象用于后台 worker 或手动恢复命令记录一次扫描/清理的效果。</p>
 */
public final class AdbLockResolveBatchResult {
  private final int scannedLocks;
  private final int rolledBackLocks;
  private final int rolledForwardLocks;

  /**
   * 创建批处理结果。
   *
   * @param scannedLocks 本轮扫描出的过期 lock 数
   * @param rolledBackLocks 本轮成功 rollback 的 lock 数
   */
  public AdbLockResolveBatchResult(int scannedLocks, int rolledBackLocks) {
    this(scannedLocks, rolledBackLocks, 0);
  }

  /**
   * 创建批处理结果。
   *
   * @param scannedLocks 本轮扫描出的过期 lock 数
   * @param rolledBackLocks 本轮成功 rollback 的 lock 数
   * @param rolledForwardLocks 本轮成功 roll-forward 的 lock 数
   */
  public AdbLockResolveBatchResult(int scannedLocks, int rolledBackLocks,
      int rolledForwardLocks) {
    if (scannedLocks < 0) {
      throw new IllegalArgumentException("scannedLocks is negative: "
          + scannedLocks);
    }
    if (rolledBackLocks < 0) {
      throw new IllegalArgumentException("rolledBackLocks is negative: "
          + rolledBackLocks);
    }
    if (rolledForwardLocks < 0) {
      throw new IllegalArgumentException("rolledForwardLocks is negative: "
          + rolledForwardLocks);
    }
    if (rolledBackLocks + rolledForwardLocks > scannedLocks) {
      throw new IllegalArgumentException(
          "resolved locks exceeds scannedLocks");
    }
    this.scannedLocks = scannedLocks;
    this.rolledBackLocks = rolledBackLocks;
    this.rolledForwardLocks = rolledForwardLocks;
  }

  public int getScannedLocks() {
    return scannedLocks;
  }

  public int getRolledBackLocks() {
    return rolledBackLocks;
  }

  public int getRolledForwardLocks() {
    return rolledForwardLocks;
  }
}
