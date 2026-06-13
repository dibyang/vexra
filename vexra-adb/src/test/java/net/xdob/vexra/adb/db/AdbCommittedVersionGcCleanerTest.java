package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.adb.key.VersionKey;
import net.xdob.vexra.adb.ldb.LdbStore;
import org.h2.value.ValueVarchar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * ADB committed version GC cleaner 测试。
 *
 * <p>验证 GC 只删除 safe point 之前的旧 committed version，并且始终保留每个
 * logical key 的最新 committed version，避免清空当前可见数据。</p>
 */
class AdbCommittedVersionGcCleanerTest {
  @TempDir
  private Path tempDir;

  /**
   * 验证 cleaner 删除旧历史版本，同时保留最新 committed version 和 intent。
   */
  @Test
  void shouldDeleteOldCommittedVersionsButKeepLatestAndIntent()
      throws Exception {
    try (LdbStore store = new LdbStore(
        tempDir.resolve("gc-history").toString())) {
      RowKey row1 = rowKey(1);
      RowKey row2 = rowKey(2);
      putCommitted(store, row1, 30);
      putCommitted(store, row1, 20);
      putCommitted(store, row1, 10);
      putCommitted(store, row2, 5);
      putIntent(store, row1, 99);

      AdbGcCleanResult result = new AdbCommittedVersionGcCleaner(store,
          new AdbGcSafePointManager(25)).cleanOnce(0);

      assertEquals(4, result.getScannedVersions());
      assertEquals(2, result.getDeletedVersions());
      assertNotNull(store.get(VersionKey.of(row1, true, 30).toBytes()));
      assertNull(store.get(VersionKey.of(row1, true, 20).toBytes()));
      assertNull(store.get(VersionKey.of(row1, true, 10).toBytes()));
      assertNotNull(store.get(VersionKey.of(row2, true, 5).toBytes()));
      assertNotNull(store.get(VersionKey.of(row1, false, 99).toBytes()));
    }
  }

  /**
   * 验证删除 limit 生效，便于后台 worker 分批清理。
   */
  @Test
  void shouldRespectDeleteLimit() throws Exception {
    try (LdbStore store = new LdbStore(
        tempDir.resolve("gc-limit").toString())) {
      RowKey row = rowKey(3);
      putCommitted(store, row, 40);
      putCommitted(store, row, 30);
      putCommitted(store, row, 20);

      AdbGcCleanResult result = new AdbCommittedVersionGcCleaner(store,
          new AdbGcSafePointManager(35)).cleanOnce(1);

      assertEquals(1, result.getDeletedVersions());
      assertNotNull(store.get(VersionKey.of(row, true, 40).toBytes()));
      assertNull(store.get(VersionKey.of(row, true, 30).toBytes()));
      assertNotNull(store.get(VersionKey.of(row, true, 20).toBytes()));
    }
  }

  private static void putCommitted(LdbStore store, RowKey key, long commitTs)
      throws Exception {
    store.put(VersionKey.of(key, true, commitTs).toBytes(),
        RowValue.encodeValue(rowValue(commitTs, commitTs,
            "committed-" + commitTs)));
  }

  private static void putIntent(LdbStore store, RowKey key, long txnId)
      throws Exception {
    store.put(VersionKey.of(key, false, txnId).toBytes(),
        RowValue.encodeValue(rowValue(txnId, 0, "intent-" + txnId)));
  }

  private static RowValue rowValue(long txnId, long commitTs, String value) {
    RowValue rowValue = new RowValue();
    rowValue.txnId = txnId;
    rowValue.commitTs = commitTs;
    rowValue.payload = RowCodec.encode(ValueVarchar.get(value));
    return rowValue;
  }

  private static RowKey rowKey(long rowId) {
    return RowKey.of(TabId.of(1, 0L), rowId);
  }
}
