package net.xdob.vexra.adb;

import net.xdob.vexra.adb.db.RowCodec;
import net.xdob.vexra.adb.db.RowValue;
import net.xdob.vexra.adb.db.Transaction2;
import net.xdob.vexra.adb.db.TxnManager;
import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.adb.ldb.LdbStore;
import org.h2.value.ValueVarchar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
}
