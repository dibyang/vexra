package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.RowPrefix;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.adb.key.VersionKey;
import net.xdob.vexra.adb.ldb.LdbStore;
import org.h2.value.ValueVarchar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

  /**
   * 验证 range count raw 快路径会跳过晚于快照的 committed 版本。
   *
   * @throws Exception store 或事务操作失败时抛出
   */
  @Test
  void shouldKeepSnapshotRangeCountWhenNewerVersionsExist()
      throws Exception {
    try (LdbStore store = new LdbStore(new File(tempDir, "range-visible")
        .getAbsolutePath())) {
      TxnManager manager = new TxnManager(store);
      TabId tabId = TabId.of(1, 0L);

      putCommitted(store, RowKey.of(tabId, 1L), 10L, "old-1");
      putCommitted(store, RowKey.of(tabId, 1L), 20L, "new-1");
      putCommitted(store, RowKey.of(tabId, 2L), 10L, "old-2");
      putDeleted(store, RowKey.of(tabId, 2L), 20L);
      putCommitted(store, RowKey.of(tabId, 3L), 10L, "old-3");

      Transaction2 reader = new Transaction2(1L, 15L);
      assertEquals(3L, manager.countVisibleRows(reader, RowPrefix.of(tabId),
          1L, 3L));

      Transaction2 latestReader = new Transaction2(2L, 25L);
      assertEquals(2L, manager.countVisibleRows(latestReader,
          RowPrefix.of(tabId), 1L, 3L));
    }
  }

  /**
   * 验证 raw range count 遇到同一逻辑行的 intent 版本后会继续读取 committed 版本。
   *
   * <p>VersionRowKey 中 intent 标记会排在 committed 标记之前。该场景要求 raw-key
   * helper 每次推进 cursor 后刷新当前 key，否则会一直拿旧 intent key 做判断，
   * 最终把后续范围全部跳过。</p>
   *
   * @throws Exception store 或事务操作失败时抛出
   */
  @Test
  void shouldContinueRawRangeCountAfterIntentVersion() throws Exception {
    try (LdbStore store = new LdbStore(new File(tempDir,
        "range-intent-visible").getAbsolutePath())) {
      TxnManager manager = new TxnManager(store);
      TabId tabId = TabId.of(1, 0L);

      putIntent(store, RowKey.of(tabId, 1L), 99L, "pending-1");
      putCommitted(store, RowKey.of(tabId, 1L), 10L, "old-1");
      putCommitted(store, RowKey.of(tabId, 2L), 10L, "old-2");

      Transaction2 reader = new Transaction2(1L, 15L);
      assertEquals(2L, manager.countVisibleRows(reader, RowPrefix.of(tabId),
          1L, 2L));
    }
  }

  /**
   * 验证带本地写的 range count 路径也会跳过晚于快照的 committed 版本。
   *
   * @throws Exception store 或事务操作失败时抛出
   */
  @Test
  void shouldKeepSnapshotRangeCountWithLocalWriteWhenNewerVersionsExist()
      throws Exception {
    try (LdbStore store = new LdbStore(new File(tempDir,
        "range-visible-local").getAbsolutePath())) {
      TxnManager manager = new TxnManager(store);
      TabId tabId = TabId.of(1, 0L);

      putCommitted(store, RowKey.of(tabId, 1L), 10L, "old-1");
      putCommitted(store, RowKey.of(tabId, 1L), 20L, "new-1");
      putCommitted(store, RowKey.of(tabId, 2L), 10L, "old-2");
      putDeleted(store, RowKey.of(tabId, 2L), 20L);
      putCommitted(store, RowKey.of(tabId, 3L), 10L, "old-3");

      Transaction2 reader = new Transaction2(1L, 15L);
      manager.put(reader, RowKey.of(tabId, 4L), row("local-4", 0L));

      assertEquals(4L, manager.countVisibleRows(reader, RowPrefix.of(tabId),
          1L, 4L));
    }
  }

  /**
   * 验证带本地写的 raw range count 会按当前事务覆盖 store 中的旧行。
   *
   * <p>该场景同时覆盖本地 delete、本地 update 和尚未落盘的本地 insert：
   * 计数必须以 write-set 为准，而不是把 store 中的旧 committed 版本重复计入。</p>
   *
   * @throws Exception store 或事务操作失败时抛出
   */
  @Test
  void shouldCountRangeWithLocalWriteOverridesOnRawPath() throws Exception {
    try (LdbStore store = new LdbStore(new File(tempDir,
        "range-visible-local-raw").getAbsolutePath())) {
      TxnManager manager = new TxnManager(store);
      TabId tabId = TabId.of(1, 0L);

      putCommitted(store, RowKey.of(tabId, 1L), 10L, "old-1");
      putCommitted(store, RowKey.of(tabId, 2L), 10L, "old-2");
      putCommitted(store, RowKey.of(tabId, 3L), 10L, "old-3");

      Transaction2 reader = new Transaction2(1L, 15L);
      manager.delete(reader, RowKey.of(tabId, 1L));
      manager.put(reader, RowKey.of(tabId, 2L), row("local-2", 0L));
      manager.put(reader, RowKey.of(tabId, 4L), row("local-4", 0L));

      assertEquals(3L, manager.countVisibleRows(reader, RowPrefix.of(tabId),
          1L, 4L));
    }
  }

  /**
   * 验证范围外本地写不会把 range count 拉入本地写覆盖路径。
   *
   * <p>mixed workload 中追加写通常落在基准数据范围之外，而 range count 仍查询旧数据范围。
   * 该场景可以安全复用无本地写 raw path，避免每次 range count 都扫描 write-set 并构造空的
   * local row map。</p>
   *
   * @throws Exception store 或事务操作失败时抛出
   */
  @Test
  void shouldSkipLocalWriteRangePathWhenBoundsDoNotOverlap()
      throws Exception {
    try (LdbStore store = new LdbStore(new File(tempDir,
        "range-visible-local-outside").getAbsolutePath())) {
      TxnManager manager = new TxnManager(store);
      AdbSqlDiagnosticRecorder recorder = new AdbSqlDiagnosticRecorder(0, 0);
      manager.setSqlDiagnosticRecorder(recorder);
      TabId tabId = TabId.of(1, 0L);

      putCommitted(store, RowKey.of(tabId, 1L), 10L, "old-1");
      putCommitted(store, RowKey.of(tabId, 2L), 10L, "old-2");
      putCommitted(store, RowKey.of(tabId, 3L), 10L, "old-3");

      Transaction2 reader = new Transaction2(1L, 15L);
      manager.put(reader, RowKey.of(tabId, 10_000L), row("local-far", 0L));

      assertFalse(reader.mayHaveLocalRowWriteInRange(tabId, 1L, 3L));
      assertEquals(3L, manager.countVisibleRows(reader, RowPrefix.of(tabId),
          1L, 3L));

      AdbSqlDiagnosticSnapshot snapshot = recorder.snapshot();
      assertTrue(snapshot.getPhaseStats().containsKey(
          "ADB_RANGE_COUNT_VISIBLE_COUNT_RAW"));
      assertFalse(snapshot.getPhaseStats().containsKey(
          "ADB_RANGE_COUNT_VISIBLE_COUNT_RAW_LOCAL"));
    }
  }

  /**
   * 验证 range scan lower bound 使用与 VersionRowKey 一致的 rowId 编码。
   *
   * <p>row version key 会对 rowId 做符号位翻转以保持 signed long 字典序。
   * 如果 range seek key 直接写原始 rowId，正数主键范围会落在真实 row key 之前，
   * 导致 {@code COUNT(*) WHERE ID BETWEEN ? AND ?} 从表前部开始扫描。</p>
   */
  @Test
  void shouldEncodeRangeSeekKeyWithVersionRowKeyOrder() {
    TabId tabId = TabId.of(1, 0L);
    RowPrefix prefix = RowPrefix.of(tabId);
    byte[] lower = TxnManager.buildRowSeekKey(prefix, 90L);
    byte[] before = VersionKey.of(RowKey.of(tabId, 89L), true, 10L)
        .toBytes();
    byte[] first = VersionKey.of(RowKey.of(tabId, 90L), true, 10L)
        .toBytes();
    byte[] after = VersionKey.of(RowKey.of(tabId, 91L), true, 10L)
        .toBytes();

    assertTrue(compareUnsigned(before, lower) < 0);
    assertTrue(compareUnsigned(lower, first) <= 0);
    assertTrue(compareUnsigned(lower, after) < 0);
  }

  /**
   * 验证单列可见值快路径会保持事务快照，并且只返回指定列。
   *
   * <p>该路径用于 JDBC 主键点查单列投影，底层会直接从 RowValue 落盘字节的 payload
   * 子区间解码列值；这里用多列 row payload 防止退化成单值解码。</p>
   */
  @Test
  void shouldDecodeVisibleColumnFromCommittedStoreValue() throws Exception {
    try (LdbStore store = new LdbStore(new File(tempDir,
        "visible-column").getAbsolutePath())) {
      TxnManager manager = new TxnManager(store);
      RowKey key = RowKey.of(TabId.of(1, 0L), 1L);

      putCommittedRow(store, key, 10L, 1L, "old-name");
      putCommittedRow(store, key, 20L, 1L, "new-name");

      Transaction2 reader = new Transaction2(1L, 15L);
      TxnManager.VisibleColumnValue visible =
          manager.getVisibleColumn(reader, key, 1);

      assertNotNull(visible);
      assertEquals(10L, visible.commitTs());
      assertEquals("old-name", visible.value().getString());
      assertEquals(Long.valueOf(10L), reader.getReadVersion(key));
    }
  }

  /**
   * 验证单列快路径在跳过更新版本时不会把旧快照值标记为最新 committed 值。
   */
  @Test
  void shouldMarkVisibleColumnAsNotLatestWhenNewerVersionExists()
      throws Exception {
    try (LdbStore store = new LdbStore(new File(tempDir,
        "visible-column-not-latest").getAbsolutePath())) {
      TxnManager manager = new TxnManager(store);
      RowKey key = RowKey.of(TabId.of(1, 0L), 1L);

      putCommittedRow(store, key, 10L, 1L, "old-name");
      putCommittedRow(store, key, 20L, 1L, "new-name");

      TxnManager.VisibleColumnValue oldVisible =
          manager.getVisibleColumn(new Transaction2(1L, 15L), key, 1);
      TxnManager.VisibleColumnValue latestVisible =
          manager.getVisibleColumn(new Transaction2(2L, 25L), key, 1);

      assertNotNull(oldVisible);
      assertEquals("old-name", oldVisible.value().getString());
      assertTrue(!oldVisible.latestCommitted());
      assertNotNull(latestVisible);
      assertEquals("new-name", latestVisible.value().getString());
      assertTrue(latestVisible.latestCommitted());
    }
  }

  /**
   * 验证 LDB 直接 restore 后会通过 store 内容世代号清理默认 trusted cache。
   *
   * <p>该用例覆盖绕过 runtime bridge 和 region snapshot installer 的直接
   * {@link LdbStore#restore(String)} 调用：同一个 TxnManager 在 restore 前缓存了较新的
   * committed row，restore 后下一次读取必须发现 store epoch 变化并回到 checkpoint 内容。</p>
   */
  @Test
  void shouldInvalidateDefaultTrustedCacheAfterDirectLdbRestore()
      throws Exception {
    File checkpointDir = new File(tempDir, "direct-restore-checkpoint");
    try (LdbStore store = new LdbStore(new File(tempDir,
        "direct-restore-cache").getAbsolutePath())) {
      TxnManager manager = new TxnManager(store);
      RowKey key = RowKey.of(TabId.of(1, 0L), 1L);

      Transaction2 beforeTxn = manager.beginTransaction();
      manager.put(beforeTxn, key, row("checkpoint-value", 0L));
      manager.commit(beforeTxn);
      store.checkpoint(checkpointDir.getAbsolutePath());

      Transaction2 afterTxn = manager.beginTransaction();
      manager.put(afterTxn, key, row("stale-cache-value", 0L));
      manager.commit(afterTxn);
      assertEquals("stale-cache-value", read(manager,
          manager.beginTransaction(), key));

      store.restore(checkpointDir.getAbsolutePath());

      assertEquals("checkpoint-value", read(manager,
          manager.beginTransaction(), key));
    }
  }

  private static void putCommitted(LdbStore store, RowKey key, long commitTs,
      String value) throws Exception {
    store.writeBatch(batch -> batch.put(VersionKey.of(key, true, commitTs)
        .toBytes(), RowValue.encodeValue(row(value, commitTs))));
  }

  private static void putIntent(LdbStore store, RowKey key, long txnId,
      String value) throws Exception {
    store.writeBatch(batch -> batch.put(VersionKey.of(key, false, txnId)
        .toBytes(), RowValue.encodeValue(row(value, 0L))));
  }

  private static void putCommittedRow(LdbStore store, RowKey key,
      long commitTs, long id, String name) throws Exception {
    store.writeBatch(batch -> batch.put(VersionKey.of(key, true, commitTs)
        .toBytes(), RowValue.encodeValue(row(id, name, commitTs))));
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

  private static RowValue row(long id, String value, long commitTs) {
    RowValue row = new RowValue();
    row.commitTs = commitTs;
    row.payload = RowCodec.encode(org.h2.value.ValueRow.get(new org.h2.value.Value[]{
        org.h2.value.ValueBigint.get(id),
        ValueVarchar.get(value)
    }));
    return row;
  }

  private static RowValue deleted(long commitTs) {
    RowValue row = new RowValue();
    row.commitTs = commitTs;
    row.deleted = true;
    row.payload = new byte[0];
    return row;
  }

  private static int compareUnsigned(byte[] left, byte[] right) {
    int length = Math.min(left.length, right.length);
    for (int i = 0; i < length; i++) {
      int diff = (left[i] & 0xff) - (right[i] & 0xff);
      if (diff != 0) {
        return diff;
      }
    }
    return left.length - right.length;
  }
}
