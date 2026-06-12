package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.adb.key.TxnLockKey;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * ADB TXN CF durable lock 扫描器。
 *
 * <p>该类从 store 的 TXN CF 扫描 {@link TxnLockKey}，并把 value 解码为
 * {@link AdbTxnLock}。它是后台 lock resolve worker、诊断命令和手动恢复工具
 * 的共享入口；扫描时只读快照，不直接修改 store。</p>
 */
public final class AdbTxnLockScanner {
  private static final byte[] FIRST_KEY = new byte[0];

  private final DbStore store;

  /**
   * 创建 lock scanner。
   *
   * @param store ADB store
   */
  public AdbTxnLockScanner(DbStore store) {
    this.store = Objects.requireNonNull(store, "store == null");
  }

  /**
   * 扫描全部 durable lock。
   *
   * @param limit 最多返回多少条，0 表示不限制
   * @return lock 记录列表
   * @throws SQLException 扫描或解码失败时抛出
   */
  public List<AdbTxnLock> scanLocks(int limit) throws SQLException {
    return scanLocks(limit, Long.MIN_VALUE, false);
  }

  /**
   * 扫描已过期 durable lock。
   *
   * @param nowTs 当前时间戳
   * @param limit 最多返回多少条，0 表示不限制
   * @return 已过期 lock 记录列表
   * @throws SQLException 扫描或解码失败时抛出
   */
  public List<AdbTxnLock> scanExpiredLocks(long nowTs, int limit)
      throws SQLException {
    return scanLocks(limit, nowTs, true);
  }

  private List<AdbTxnLock> scanLocks(int limit, long nowTs,
      boolean expiredOnly) throws SQLException {
    if (limit < 0) {
      throw new IllegalArgumentException("limit is negative: " + limit);
    }
    List<AdbTxnLock> locks = new ArrayList<>();
    try (VersionScanSource scan = store.openVersionScanSource(
        CF.TXN.getCfId(), ScanDirection.FORWARD)) {
      scan.seekToRangeStart(FIRST_KEY, null);
      while (scan.isValid() && (limit == 0 || locks.size() < limit)) {
        byte[] key = scan.key();
        if (TxnLockKey.matches(key)) {
          AdbTxnLock lock = AdbTxnLock.fromBytes(scan.value());
          if (!expiredOnly || lock.isExpired(nowTs)) {
            locks.add(lock);
          }
        }
        scan.advance();
      }
      return locks;
    } catch (SQLException e) {
      throw e;
    } catch (Exception e) {
      throw new SQLException("Failed to scan ADB txn locks", e);
    }
  }
}
