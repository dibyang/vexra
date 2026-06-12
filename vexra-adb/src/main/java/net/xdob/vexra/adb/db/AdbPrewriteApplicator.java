package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.adb.key.DataKey;
import net.xdob.vexra.adb.key.TxnKeyType;
import net.xdob.vexra.adb.key.TxnLockKey;
import net.xdob.vexra.adb.key.TxnRefKey;
import net.xdob.vexra.adb.key.VersionKey;

import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;

/**
 * ADB MVCC prewrite 落盘器。
 *
 * <p>该类把 region 2PC PREWRITE mutation 写入现有 ADB 磁盘语义：DEFAULT CF
 * 中保存未提交 {@link VersionKey} intent，TXN CF 中保存 txnId 到 intent key 的
 * {@link TxnRefKey} 索引。Commit/Rollback 后续继续复用
 * {@link DbStore#commitAsync(long, long, java.util.List)} 和
 * {@link DbStore#rollbackAsync(long)}。</p>
 */
public final class AdbPrewriteApplicator {
  private AdbPrewriteApplicator() {
  }

  /**
   * 将 region mutation 预写为 durable MVCC intent。
   *
   * @param store ADB store
   * @param txnId 当前事务 ID
   * @param startTs 当前事务 start timestamp，用于基础写冲突检查
   * @param mutations 当前 region 的 mutation 集合
   * @throws SQLException 当冲突检查或落盘失败时抛出
   */
  public static void prewrite(DbStore store, long txnId, long startTs,
      Collection<AdbRegionMutation> mutations) throws SQLException {
    prewrite(store, txnId, startTs, mutations, Collections.emptyList());
  }

  /**
   * 将 region mutation 和 lock record 原子预写为 durable MVCC 状态。
   *
   * @param store ADB store
   * @param txnId 当前事务 ID
   * @param startTs 当前事务 start timestamp，用于基础写冲突检查
   * @param mutations 当前 region 的 mutation 集合
   * @param locks 当前 region 的 lock record 集合
   * @throws SQLException 当冲突检查或落盘失败时抛出
   */
  public static void prewrite(DbStore store, long txnId, long startTs,
      Collection<AdbRegionMutation> mutations, Collection<AdbTxnLock> locks)
      throws SQLException {
    Objects.requireNonNull(store, "store == null");
    Objects.requireNonNull(mutations, "mutations == null");
    Objects.requireNonNull(locks, "locks == null");
    if (txnId < 0) {
      throw new IllegalArgumentException("txnId is negative: " + txnId);
    }
    if (startTs < 0) {
      throw new IllegalArgumentException("startTs is negative: " + startTs);
    }
    if (mutations.isEmpty()) {
      throw new IllegalArgumentException("mutations is empty");
    }

    store.writeBatch(batch -> {
      for (AdbRegionMutation mutation : mutations) {
        applyMutation(batch, txnId, startTs,
            Objects.requireNonNull(mutation, "mutation == null"));
      }
      for (AdbTxnLock lock : locks) {
        applyLock(batch, txnId, Objects.requireNonNull(lock,
            "lock == null"));
      }
    });
  }

  private static void applyMutation(AdbWriteBatch batch, long txnId,
      long startTs, AdbRegionMutation mutation) throws SQLException {
    DataKey key = mutation.getKey();
    VersionKey intentKey = VersionKey.of(key, false, txnId);

    assertNoForeignIntent(batch.getStore(), txnId, intentKey);
    assertNoForeignIntentForLogicalKey(batch.getStore(), txnId, key);
    assertNoNewerCommittedVersion(batch.getStore(), key, startTs);

    RowValue value = mutation.getValue();
    value.txnId = txnId;
    value.commitTs = 0;
    TxnRefKey txnRefKey = TxnRefKey.of(txnId, TxnKeyType.WRITE_REF,
        CF.DEFAULT.getCfId(), intentKey);

    /*
     * 这里保持与 Transaction2.put/delete 相同的磁盘形态。这样 prewrite 之后，
     * 现有 commit/rollback 无需感知请求来源，就能按 txn ref 找到并处理 intent。
     */
    batch.put(intentKey.toBytes(), RowValue.encodeValue(value));
    batch.put(CF.TXN.getCfId(), txnRefKey.toBytes(), new byte[0]);
  }

  private static void applyLock(AdbWriteBatch batch, long txnId,
      AdbTxnLock lock) throws SQLException {
    if (lock.getTxnId() != txnId) {
      throw new SQLException("ADB prewrite lock txn mismatch, txnId=" + txnId
          + ", lockTxnId=" + lock.getTxnId());
    }
    TxnLockKey lockKey = TxnLockKey.of(txnId, CF.DEFAULT.getCfId(),
        lock.getKey());
    batch.put(CF.TXN.getCfId(), lockKey.toBytes(), lock.toBytes());
  }

  private static void assertNoForeignIntentForLogicalKey(DbStore store,
      long txnId, DataKey key) throws SQLException {
    byte[] prefix = key.toBytes();
    byte[] end = KeyCodec.prefixEnd(prefix);
    try (VersionScanSource scan = store.openVersionScanSource(
        ScanDirection.FORWARD)) {
      scan.seekToRangeStart(prefix, end);
      while (scan.isValid() && KeyCodec.startsWith(scan.key(), prefix)) {
        VersionKey versionKey = VersionKey.fromBytes(scan.key());
        RowValue value = RowValue.decodeValue(scan.value());
        if (!versionKey.isCommited() && value != null
            && value.txnId != txnId) {
          throw new SQLException("ADB prewrite lock conflict, txnId=" + txnId
              + ", ownerTxnId=" + value.txnId);
        }
        scan.advance();
      }
    } catch (SQLException e) {
      throw e;
    } catch (Exception e) {
      throw new SQLException("Failed to scan ADB prewrite intents", e);
    }
  }

  private static void assertNoForeignIntent(DbStore store, long txnId,
      VersionKey intentKey) throws SQLException {
    byte[] existing = store.get(CF.DEFAULT.getCfId(), intentKey.toBytes());
    if (existing == null) {
      return;
    }
    RowValue value = RowValue.decodeValue(existing);
    if (value != null && value.txnId != txnId) {
      throw new SQLException("ADB prewrite conflict on intent key, txnId="
          + txnId + ", ownerTxnId=" + value.txnId);
    }
  }

  private static void assertNoNewerCommittedVersion(DbStore store,
      DataKey key, long startTs) throws SQLException {
    RowValue latest = new DefaultVersionResolver(store).getLatestCommitted(key);
    if (latest != null && latest.commitTs > startTs) {
      throw new SQLException("ADB prewrite write conflict, key=" + key
          + ", startTs=" + startTs + ", latestCommitTs=" + latest.commitTs);
    }
  }
}
