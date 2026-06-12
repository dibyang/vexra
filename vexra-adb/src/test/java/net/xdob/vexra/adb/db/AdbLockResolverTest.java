package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.adb.key.TxnKeyType;
import net.xdob.vexra.adb.key.TxnRefKey;
import net.xdob.vexra.adb.key.VersionKey;
import net.xdob.vexra.adb.ldb.LdbStore;
import org.h2.value.ValueVarchar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * ADB lock resolver 测试。
 *
 * <p>验证 ADB-Prod-02 的最小 lock resolve 入口能够复用现有 rollback durable
 * 语义清理过期 intent，同时不会误清理仍在 TTL 内的 lock。</p>
 */
class AdbLockResolverTest {
  @TempDir
  private Path tempDir;

  /**
   * 验证过期 lock 会触发 rollback 并删除 intent/ref。
   */
  @Test
  void shouldRollbackExpiredLock() throws Exception {
    try (LdbStore store = new LdbStore(tempDir.resolve("expired").toString())) {
      RowKey key = rowKey(1);
      prewrite(store, 10, key);
      VersionKey intentKey = VersionKey.of(key, false, 10);
      TxnRefKey txnRefKey = TxnRefKey.of(10, TxnKeyType.WRITE_REF,
          CF.DEFAULT.getCfId(), intentKey);

      AdbLockResolveAction action = new AdbLockResolver(store)
          .resolveExpiredLock(lock(10, key, 1, 5), 7);

      assertEquals(AdbLockResolveAction.ROLLED_BACK, action);
      assertNull(store.get(intentKey.toBytes()));
      assertNull(store.get(CF.TXN.getCfId(), txnRefKey.toBytes()));
    }
  }

  /**
   * 验证未过期 lock 只返回 WAIT，不会清理 durable intent。
   */
  @Test
  void shouldWaitForUnexpiredLock() throws Exception {
    try (LdbStore store = new LdbStore(
        tempDir.resolve("unexpired").toString())) {
      RowKey key = rowKey(2);
      prewrite(store, 11, key);
      VersionKey intentKey = VersionKey.of(key, false, 11);
      TxnRefKey txnRefKey = TxnRefKey.of(11, TxnKeyType.WRITE_REF,
          CF.DEFAULT.getCfId(), intentKey);

      AdbLockResolveAction action = new AdbLockResolver(store)
          .resolveExpiredLock(lock(11, key, 1, 10), 5);

      assertEquals(AdbLockResolveAction.WAIT, action);
      assertNotNull(store.get(intentKey.toBytes()));
      assertNotNull(store.get(CF.TXN.getCfId(), txnRefKey.toBytes()));
    }
  }

  /**
   * 验证 resolver 可以从 durable lock scanner 批量清理过期锁。
   */
  @Test
  void shouldResolveExpiredLocksFromStore() throws Exception {
    try (LdbStore store = new LdbStore(
        tempDir.resolve("batch-resolve").toString())) {
      RowKey expired = rowKey(3);
      RowKey waiting = rowKey(4);
      prewrite(store, 12, expired, 1, 5);
      prewrite(store, 13, waiting, 10, 20);

      AdbLockResolveBatchResult result = new AdbLockResolver(store)
          .resolveExpiredLocks(7, 0);

      assertEquals(1, result.getScannedLocks());
      assertEquals(1, result.getRolledBackLocks());
      assertEquals(0, result.getRolledForwardLocks());
      assertNull(store.get(VersionKey.of(expired, false, 12).toBytes()));
      assertNotNull(store.get(VersionKey.of(waiting, false, 13).toBytes()));
    }
  }

  /**
   * 验证 primary 已提交时，过期 secondary lock 会按 primary commitTs 前滚。
   */
  @Test
  void shouldRollForwardSecondaryWhenPrimaryCommitted() throws Exception {
    try (LdbStore store = new LdbStore(
        tempDir.resolve("primary-committed").toString())) {
      RowKey primary = rowKey(5);
      RowKey secondary = rowKey(6);
      prewrite(store, 14, primary, 1, 5);
      store.commitAsync(14, 30, Collections.emptyList()).join();
      prewrite(store, 14, secondary, primary, 1, 5);

      AdbLockResolveAction action = new AdbLockResolver(store)
          .resolveExpiredLock(lock(14, secondary, primary, 1, 5), 7);

      assertEquals(AdbLockResolveAction.ROLLED_FORWARD, action);
      assertNull(store.get(VersionKey.of(secondary, false, 14).toBytes()));
      assertNotNull(store.get(VersionKey.of(secondary, true, 30).toBytes()));
    }
  }

  /**
   * 验证批量 resolve 能在同一轮统计 rollback 和 roll-forward。
   */
  @Test
  void shouldCountRollForwardInBatchResolve() throws Exception {
    try (LdbStore store = new LdbStore(
        tempDir.resolve("batch-forward").toString())) {
      RowKey primary = rowKey(7);
      RowKey secondary = rowKey(8);
      RowKey rollback = rowKey(9);
      prewrite(store, 15, primary, 1, 5);
      store.commitAsync(15, 40, Collections.emptyList()).join();
      prewrite(store, 15, secondary, primary, 1, 5);
      prewrite(store, 16, rollback, 1, 5);

      AdbLockResolveBatchResult result = new AdbLockResolver(store)
          .resolveExpiredLocks(7, 0);

      assertEquals(2, result.getScannedLocks());
      assertEquals(1, result.getRolledBackLocks());
      assertEquals(1, result.getRolledForwardLocks());
      assertNotNull(store.get(VersionKey.of(secondary, true, 40).toBytes()));
      assertNull(store.get(VersionKey.of(rollback, false, 16).toBytes()));
    }
  }

  private static void prewrite(LdbStore store, long txnId, RowKey key)
      throws Exception {
    AdbPrewriteApplicator.prewrite(store, txnId, 1,
        Collections.singletonList(new AdbRegionMutation(key,
            rowValue(txnId, "lock-resolve"))));
  }

  private static void prewrite(LdbStore store, long txnId, RowKey key,
      long startTs, long ttlMillis) throws Exception {
    prewrite(store, txnId, key, key, startTs, ttlMillis);
  }

  private static void prewrite(LdbStore store, long txnId, RowKey key,
      RowKey primaryKey, long startTs, long ttlMillis) throws Exception {
    AdbPrewriteApplicator.prewrite(store, txnId, startTs,
        Collections.singletonList(new AdbRegionMutation(key,
            rowValue(txnId, "lock-resolve"))),
        Collections.singletonList(lock(txnId, key, primaryKey, startTs,
            ttlMillis)));
  }

  private static AdbTxnLock lock(long txnId, RowKey key, long startTs,
      long ttlMillis) {
    return lock(txnId, key, key, startTs, ttlMillis);
  }

  private static AdbTxnLock lock(long txnId, RowKey key, RowKey primaryKey,
      long startTs, long ttlMillis) {
    return new AdbTxnLock(txnId, key.toBytes(), primaryKey.toBytes(), startTs,
        "r1", ttlMillis);
  }

  private static RowValue rowValue(long txnId, String value) {
    RowValue rowValue = new RowValue();
    rowValue.txnId = txnId;
    rowValue.payload = RowCodec.encode(ValueVarchar.get(value));
    return rowValue;
  }

  private static RowKey rowKey(long rowId) {
    return RowKey.of(TabId.of(1, 0L), rowId);
  }
}
