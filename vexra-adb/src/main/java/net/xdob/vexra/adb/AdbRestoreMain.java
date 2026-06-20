package net.xdob.vexra.adb;

/**
 * ADB 本地全量恢复命令入口。
 */
public final class AdbRestoreMain {
  public static final String MAIN_CLASS = "net.xdob.vexra.adb.AdbRestoreMain";

  private AdbRestoreMain() {
  }

  /**
   * 执行本地 FULL restore。
   *
   * @param args `--storeDir path --location path`
   * @throws Exception 恢复失败时抛出
   */
  public static void main(String[] args) throws Exception {
    AdbStoreBackupRestoreMain.restore(args);
  }
}
