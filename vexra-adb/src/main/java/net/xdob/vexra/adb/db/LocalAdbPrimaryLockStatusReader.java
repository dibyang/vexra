package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.adb.key.VersionKey;

import java.sql.SQLException;
import java.util.Objects;

/**
 * 基于当前 store 的 primary lock 状态读取器。
 *
 * <p>该实现保持现有单机/同 region resolver 语义：扫描 primary logical key 下的
 * committed version，只有发现同一 txnId 的 committed value 时才返回 committed。</p>
 */
public final class LocalAdbPrimaryLockStatusReader
    implements AdbPrimaryLockStatusReader {
  private final DbStore store;

  /**
   * 创建本地 primary 状态读取器。
   *
   * @param store ADB store
   */
  public LocalAdbPrimaryLockStatusReader(DbStore store) {
    this.store = Objects.requireNonNull(store, "store == null");
  }

  @Override
  public AdbPrimaryLockStatus readPrimaryStatus(AdbTxnLock lock)
      throws SQLException {
    Objects.requireNonNull(lock, "lock == null");
    byte[] prefix = lock.getPrimaryKey();
    byte[] end = KeyCodec.prefixEnd(prefix);
    try (VersionScanSource scan = store.openVersionScanSource(
        ScanDirection.FORWARD)) {
      scan.seekToRangeStart(prefix, end);
      while (scan.isValid() && KeyCodec.startsWith(scan.key(), prefix)) {
        VersionKey versionKey = VersionKey.fromBytes(scan.key());
        if (versionKey.isCommited()) {
          RowValue rowValue = RowValue.decodeValue(scan.value());
          if (rowValue != null && rowValue.txnId == lock.getTxnId()) {
            long commitTs = rowValue.commitTs > 0
                ? rowValue.commitTs : versionKey.getCommitTs();
            return AdbPrimaryLockStatus.committed(commitTs);
          }
        }
        scan.advance();
      }
      return AdbPrimaryLockStatus.unknown();
    } catch (SQLException e) {
      throw e;
    } catch (Exception e) {
      throw new SQLException("Failed to inspect primary lock, txnId="
          + lock.getTxnId(), e);
    }
  }
}
