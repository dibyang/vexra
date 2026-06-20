package net.xdob.vexra.adb;

import net.xdob.vexra.adb.db.RowCodec;
import net.xdob.vexra.adb.db.RowValue;
import net.xdob.vexra.adb.db.Transaction2;
import net.xdob.vexra.adb.db.TxnManager;
import net.xdob.vexra.adb.db.AdbUnsupportedProductionFeatureException;
import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.adb.ldb.LdbStore;
import org.h2.value.ValueVarchar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ADB 本地备份恢复命令测试。
 *
 * <p>测试直接调用命令入口，验证 `adb-backup` / `adb-restore` 使用的 main class
 * 能通过现有 LDB checkpoint/restore 恢复可见数据。</p>
 */
class AdbStoreBackupRestoreMainTest {
  @TempDir
  Path tempDir;

  /**
   * 验证备份脚本入口和恢复脚本入口可以恢复 checkpoint 中的数据。
   */
  @Test
  void shouldBackupAndRestoreLdbStoreThroughCommandEntrypoints()
      throws Exception {
    Path storeDir = tempDir.resolve("store");
    Path backupDir = tempDir.resolve("backup");
    RowKey key = rowKey(1);

    writeValue(storeDir, key, "before-backup");
    AdbBackupMain.main(new String[]{"--storeDir", path(storeDir),
        "--location", path(backupDir), "--planId", "backup-test",
        "--checkpointTs", "1"});
    writeValue(storeDir, key, "after-backup");

    AdbRestoreMain.main(new String[]{"--storeDir", path(storeDir),
        "--location", path(backupDir), "--planId", "restore-test"});

    assertEquals("before-backup", readValue(storeDir, key));
  }

  /**
   * 验证显式生产参数缺少安全默认值时，备份命令在打开 store 前拒绝执行。
   */
  @Test
  void shouldRejectBackupWhenProductionGuardIsNotReady() {
    Path storeDir = tempDir.resolve("reject-store");
    Path backupDir = tempDir.resolve("reject-backup");

    SQLException error = assertThrows(SQLException.class,
        () -> AdbBackupMain.main(new String[]{"--storeDir", path(storeDir),
            "--location", path(backupDir),
            "--adb.production.mode", "mvp-cluster",
            "--adb.production.topology", "2data1witness"}));

    assertEquals(AdbUnsupportedProductionFeatureException.SQL_STATE,
        error.getSQLState());
  }

  /**
   * 验证安全 2 data + witness 生产参数允许本地 FULL 备份恢复入口继续执行。
   */
  @Test
  void shouldAllowBackupAndRestoreWithSecureProductionGuard()
      throws Exception {
    Path storeDir = tempDir.resolve("prod-store");
    Path backupDir = tempDir.resolve("prod-backup");
    RowKey key = rowKey(2);

    writeValue(storeDir, key, "prod-before-backup");
    AdbBackupMain.main(withSecureProductionArgs("--storeDir", path(storeDir),
        "--location", path(backupDir), "--planId", "prod-backup",
        "--checkpointTs", "1"));
    writeValue(storeDir, key, "prod-after-backup");

    AdbRestoreMain.main(withSecureProductionArgs("--storeDir", path(storeDir),
        "--location", path(backupDir), "--planId", "prod-restore"));

    assertEquals("prod-before-backup", readValue(storeDir, key));
  }

  private static void writeValue(Path storeDir, RowKey key, String value)
      throws Exception {
    try (LdbStore store = new LdbStore(path(storeDir))) {
      TxnManager manager = new TxnManager(store);
      Transaction2 txn = manager.beginTransaction();
      RowValue rowValue = new RowValue();
      rowValue.payload = RowCodec.encode(ValueVarchar.get(value));
      manager.put(txn, key, rowValue);
      manager.commit(txn);
    }
  }

  private static String readValue(Path storeDir, RowKey key) throws Exception {
    try (LdbStore store = new LdbStore(path(storeDir))) {
      TxnManager manager = new TxnManager(store);
      RowValue value = manager.getVisible(manager.beginTransaction(), key);
      assertNotNull(value);
      return RowCodec.decode(value.payload).getString();
    }
  }

  private static RowKey rowKey(long rowId) {
    return RowKey.of(TabId.of(1, 0L), rowId);
  }

  private static String path(Path path) {
    return path.toAbsolutePath().toString();
  }

  private static String[] withSecureProductionArgs(String... args) {
    String[] production = new String[] {
        "--adb.production.mode", "mvp-cluster",
        "--adb.production.topology", "2data1witness",
        "--adb.security.tls.enabled", "true",
        "--adb.security.auth.enabled", "true",
        "--adb.security.leastPrivilege.enabled", "true"
    };
    String[] merged = new String[args.length + production.length];
    System.arraycopy(args, 0, merged, 0, args.length);
    System.arraycopy(production, 0, merged, args.length, production.length);
    return merged;
  }
}
