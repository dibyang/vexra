package net.xdob.vexra.adb;

/**
 * ADB 本地全量备份命令入口。
 */
public final class AdbBackupMain {
  public static final String MAIN_CLASS = "net.xdob.vexra.adb.AdbBackupMain";

  private AdbBackupMain() {
  }

  /**
   * 执行本地 FULL backup。
   *
   * @param args `--storeDir path --location path`
   * @throws Exception 备份失败时抛出
   */
  public static void main(String[] args) throws Exception {
    AdbStoreBackupRestoreMain.backup(args);
  }
}
