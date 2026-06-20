package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.adb.key.TxnKeyType;
import net.xdob.vexra.adb.key.TxnLockKey;
import net.xdob.vexra.adb.key.TxnRefKey;
import net.xdob.vexra.adb.key.VersionKey;
import net.xdob.vexra.adb.ldb.LdbStore;
import org.h2.value.ValueVarchar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ADB prewrite applicator 测试。
 *
 * <p>验证 ADB-Prod-01 的真实 PREWRITE 会落成现有 MVCC intent/ref 形态，并可被
 * 现有 commit/rollback 流程继续处理。</p>
 */
class AdbPrewriteApplicatorTest {
  @TempDir
  private Path tempDir;

  /**
   * 验证 prewrite 后 commit 会把未提交 intent 提升为 committed version。
   */
  @Test
  void shouldPrewriteIntentAndCommitIt() throws Exception {
    try (LdbStore store = new LdbStore(tempDir.resolve("commit").toString())) {
      RowKey key = rowKey(1);

      AdbPrewriteApplicator.prewrite(store, 10, 1,
          Collections.singletonList(new AdbRegionMutation(key,
              rowValue(10, "prewrite-value", false))));

      VersionKey intentKey = VersionKey.of(key, false, 10);
      TxnRefKey txnRefKey = TxnRefKey.of(10, TxnKeyType.WRITE_REF,
          CF.DEFAULT.getCfId(), intentKey);
      assertNotNull(store.get(intentKey.toBytes()));
      assertNotNull(store.get(CF.TXN.getCfId(), txnRefKey.toBytes()));

      store.commitAsync(10, 20, Collections.emptyList()).join();

      assertNull(store.get(intentKey.toBytes()));
      assertNull(store.get(CF.TXN.getCfId(), txnRefKey.toBytes()));
      RowValue committed = RowValue.decodeValue(
          store.get(VersionKey.of(key, true, 20).toBytes()));
      assertNotNull(committed);
      assertEquals(20, committed.commitTs);
      assertEquals("prewrite-value", RowCodec.decode(committed.payload)
          .getString());
    }
  }

  /**
   * 验证 prewrite 后 rollback 会清理 intent 和 txn ref。
   */
  @Test
  void shouldPrewriteIntentAndRollbackIt() throws Exception {
    try (LdbStore store = new LdbStore(tempDir.resolve("rollback").toString())) {
      RowKey key = rowKey(2);

      AdbPrewriteApplicator.prewrite(store, 11, 1,
          Collections.singletonList(new AdbRegionMutation(key,
              rowValue(11, "rollback-value", false))));
      VersionKey intentKey = VersionKey.of(key, false, 11);
      TxnRefKey txnRefKey = TxnRefKey.of(11, TxnKeyType.WRITE_REF,
          CF.DEFAULT.getCfId(), intentKey);

      store.rollbackAsync(11).join();

      assertNull(store.get(intentKey.toBytes()));
      assertNull(store.get(CF.TXN.getCfId(), txnRefKey.toBytes()));
    }
  }

  /**
   * 验证带 lock record 的 prewrite 会在 TXN CF 持久化 lock，并在 commit 后清理。
   */
  @Test
  void shouldPrewriteDurableLockRecordAndCleanItOnCommit() throws Exception {
    try (LdbStore store = new LdbStore(
        tempDir.resolve("lock-commit").toString())) {
      RowKey key = rowKey(4);
      AdbTxnLock lock = lock(14, key, 1, 3000);

      AdbPrewriteApplicator.prewrite(store, 14, 1,
          Collections.singletonList(new AdbRegionMutation(key,
              rowValue(14, "lock-commit", false))),
          Collections.singletonList(lock));

      TxnLockKey lockKey = TxnLockKey.of(14, CF.DEFAULT.getCfId(),
          key.toBytes());
      AdbTxnLock stored = AdbTxnLock.fromBytes(
          store.get(CF.TXN.getCfId(), lockKey.toBytes()));
      assertEquals(14, stored.getTxnId());
      assertEquals("r1", stored.getRegionId());

      store.commitAsync(14, 24, Collections.emptyList()).join();

      assertNull(store.get(CF.TXN.getCfId(), lockKey.toBytes()));
    }
  }

  /**
   * 验证 rollback 会同步清理 durable lock record。
   */
  @Test
  void shouldCleanDurableLockRecordOnRollback() throws Exception {
    try (LdbStore store = new LdbStore(
        tempDir.resolve("lock-rollback").toString())) {
      RowKey key = rowKey(5);
      AdbTxnLock lock = lock(15, key, 1, 3000);

      AdbPrewriteApplicator.prewrite(store, 15, 1,
          Collections.singletonList(new AdbRegionMutation(key,
              rowValue(15, "lock-rollback", false))),
          Collections.singletonList(lock));
      TxnLockKey lockKey = TxnLockKey.of(15, CF.DEFAULT.getCfId(),
          key.toBytes());
      assertNotNull(store.get(CF.TXN.getCfId(), lockKey.toBytes()));

      store.rollbackAsync(15).join();

      assertNull(store.get(CF.TXN.getCfId(), lockKey.toBytes()));
    }
  }

  /**
   * 验证同一 logical key 上已有其他事务 intent 时 prewrite 会失败。
   */
  @Test
  void shouldRejectForeignIntentOnSameLogicalKey() throws Exception {
    try (LdbStore store = new LdbStore(tempDir.resolve("conflict").toString())) {
      RowKey key = rowKey(3);
      AdbPrewriteApplicator.prewrite(store, 12, 1,
          Collections.singletonList(new AdbRegionMutation(key,
              rowValue(12, "owner", false))));

      SQLException error = assertThrows(SQLException.class,
          () -> AdbPrewriteApplicator.prewrite(store, 13, 1,
              Collections.singletonList(new AdbRegionMutation(key,
                  rowValue(13, "contender", false)))));

      assertFalse(error.getMessage().trim().isEmpty());
      assertEquals(AdbTransactionConflictException.SQL_STATE,
          error.getSQLState());
      assertEquals(AdbTransactionConflictException.ERROR_CODE,
          error.getErrorCode());
    }
  }

  private static RowValue rowValue(long txnId, String value, boolean deleted) {
    RowValue rowValue = new RowValue();
    rowValue.txnId = txnId;
    rowValue.deleted = deleted;
    rowValue.payload = RowCodec.encode(ValueVarchar.get(value));
    return rowValue;
  }

  private static AdbTxnLock lock(long txnId, RowKey key, long startTs,
      long ttlMillis) {
    return new AdbTxnLock(txnId, key.toBytes(), key.toBytes(), startTs, "r1",
        ttlMillis);
  }

  private static RowKey rowKey(long rowId) {
    return RowKey.of(TabId.of(1, 0L), rowId);
  }
}
