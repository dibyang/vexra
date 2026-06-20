package net.xdob.vexra.adb.db;

/**
 * ADB 备份 safe point 保护记录。
 *
 * <p>备份任务在读取历史快照期间需要阻止 GC safe point 越过其读取时间戳。
 * 该值对象记录备份标识和需要保护的 safe point，供运行时推进器合并判断。</p>
 */
public final class AdbBackupSafePoint {
  private final String backupId;
  private final long safePoint;

  /**
   * 创建备份 safe point 记录。
   *
   * @param backupId 备份任务标识
   * @param safePoint 备份需要保护的读取时间戳
   */
  public AdbBackupSafePoint(String backupId, long safePoint) {
    if (backupId == null || backupId.trim().isEmpty()) {
      throw new IllegalArgumentException("backupId is empty");
    }
    if (safePoint < 0) {
      throw new IllegalArgumentException("safePoint is negative: "
          + safePoint);
    }
    this.backupId = backupId.trim();
    this.safePoint = safePoint;
  }

  public String getBackupId() {
    return backupId;
  }

  public long getSafePoint() {
    return safePoint;
  }
}
