package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.adb.key.VersionKey;
import net.xdob.vexra.adb.ldb.LdbStore;
import org.h2.value.ValueVarchar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * TxnManager 可见行快路径测试。
 *
 * <p>覆盖默认 SQL 读路径会复用的 committed row raw-key scan。该测试直接使用
 * LdbStore 与 TxnManager，避免 H2 session 隔离级别干扰，确保旧事务快照不会读到
 * 后续提交的新版本。</p>
 */
class TxnManagerVisibleRowFastPathTest {

  @TempDir
  File tempDir;

  /**
   * 验证默认 getVisible 路径会跳过晚于读事务 startTs 的 committed 版本。
   *
   * @throws Exception store 或事务操作失败时抛出
   */
  @Test
  void shouldKeepSnapshotVisibleWhenNewerCommittedVersionExists()
      throws Exception {
    try (LdbStore store = new LdbStore(new File(tempDir, "visible-fast")
        .getAbsolutePath())) {
      TxnManager manager = new TxnManager(store);
      RowKey key = RowKey.of(TabId.of(1, 0L), 1L);

      putCommitted(store, key, 10L, "old");
      putCommitted(store, key, 20L, "new");

      Transaction2 reader = new Transaction2(1L, 15L);
      assertEquals("old", read(manager, reader, key));
      assertEquals("old", read(manager, reader, key));

      Transaction2 latestReader = new Transaction2(2L, 25L);
      assertEquals("new", read(manager, latestReader, key));
    }
  }

  /**
   * 验证 raw-key 提交时间戳快路径会正确跳过晚于快照的删除版本。
   *
   * @throws Exception store 或事务操作失败时抛出
   */
  @Test
  void shouldKeepSnapshotVisibleWhenNewerDeleteVersionExists()
      throws Exception {
    try (LdbStore store = new LdbStore(new File(tempDir, "visible-delete")
        .getAbsolutePath())) {
      TxnManager manager = new TxnManager(store);
      RowKey key = RowKey.of(TabId.of(1, 0L), 1L);

      putCommitted(store, key, 10L, "old");
      putDeleted(store, key, 20L);

      Transaction2 reader = new Transaction2(1L, 15L);
      assertEquals("old", read(manager, reader, key));

      Transaction2 latestReader = new Transaction2(2L, 25L);
      RowValue latestVisible = manager.getVisible(latestReader, key);
      assertNull(latestVisible);
    }
  }

  private static void putCommitted(LdbStore store, RowKey key, long commitTs,
      String value) throws Exception {
    store.writeBatch(batch -> batch.put(VersionKey.of(key, true, commitTs)
        .toBytes(), RowValue.encodeValue(row(value, commitTs))));
  }

  private static void putDeleted(LdbStore store, RowKey key, long commitTs)
      throws Exception {
    store.writeBatch(batch -> batch.put(VersionKey.of(key, true, commitTs)
        .toBytes(), RowValue.encodeValue(deleted(commitTs))));
  }

  private static String read(TxnManager manager, Transaction2 txn, RowKey key)
      throws Exception {
    RowValue visible = manager.getVisible(txn, key);
    return RowCodec.decode(visible.payload).getString();
  }

  private static RowValue row(String value, long commitTs) {
    RowValue row = new RowValue();
    row.commitTs = commitTs;
    row.payload = RowCodec.encode(ValueVarchar.get(value));
    return row;
  }

  private static RowValue deleted(long commitTs) {
    RowValue row = new RowValue();
    row.commitTs = commitTs;
    row.deleted = true;
    row.payload = new byte[0];
    return row;
  }
}
