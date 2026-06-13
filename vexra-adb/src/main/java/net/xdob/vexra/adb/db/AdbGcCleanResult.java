package net.xdob.vexra.adb.db;

/**
 * ADB committed version GC 清理结果。
 *
 * <p>用于记录一轮 GC 扫描看到的 committed version 数量和实际删除数量，后续可被
 * 后台 worker、诊断命令或 system table 暴露。</p>
 */
public final class AdbGcCleanResult {
  private final int scannedVersions;
  private final int deletedVersions;

  /**
   * 创建 GC 清理结果。
   *
   * @param scannedVersions 扫描到的 committed version 数量
   * @param deletedVersions 删除的历史 committed version 数量
   */
  public AdbGcCleanResult(int scannedVersions, int deletedVersions) {
    if (scannedVersions < 0) {
      throw new IllegalArgumentException("scannedVersions is negative: "
          + scannedVersions);
    }
    if (deletedVersions < 0) {
      throw new IllegalArgumentException("deletedVersions is negative: "
          + deletedVersions);
    }
    if (deletedVersions > scannedVersions) {
      throw new IllegalArgumentException(
          "deletedVersions exceeds scannedVersions");
    }
    this.scannedVersions = scannedVersions;
    this.deletedVersions = deletedVersions;
  }

  public int getScannedVersions() {
    return scannedVersions;
  }

  public int getDeletedVersions() {
    return deletedVersions;
  }
}
