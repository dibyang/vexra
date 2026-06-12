package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.DbStore;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;

/**
 * ADB MVCC lock resolver。
 *
 * <p>该类是 ADB-Prod-02 的最小运行时入口：当调用方确认 lock 已超过 TTL，
 * resolver 复用现有 `DbStore.rollbackAsync(txnId)` durable 语义，清理未提交
 * intent 和 TXN CF 中的 `TxnRefKey`。后续 primary/secondary resolve 和后台 worker
 * 继续在该边界上扩展。</p>
 */
public final class AdbLockResolver {
  private final DbStore store;

  /**
   * 创建 lock resolver。
   *
   * @param store ADB store，负责执行 rollback
   */
  public AdbLockResolver(DbStore store) {
    this.store = Objects.requireNonNull(store, "store == null");
  }

  /**
   * 解析一个可能过期的 lock。
   *
   * @param lock ADB lock 记录
   * @param nowTs 当前时间戳
   * @return resolver 动作结果
   * @throws SQLException rollback 失败时抛出
   */
  public AdbLockResolveAction resolveExpiredLock(AdbTxnLock lock, long nowTs)
      throws SQLException {
    Objects.requireNonNull(lock, "lock == null");
    if (!lock.isExpired(nowTs)) {
      return AdbLockResolveAction.WAIT;
    }
    rollback(lock.getTxnId());
    return AdbLockResolveAction.ROLLED_BACK;
  }

  /**
   * 扫描并解析一批已过期 durable lock。
   *
   * <p>该方法先从 TXN CF 读取过期 lock 快照，再逐条 rollback 对应 txnId。
   * 这样可以避免边扫描边修改底层 store 游标。当前增量只实现过期 rollback 路径。</p>
   *
   * @param nowTs 当前时间戳
   * @param limit 最多处理多少条，0 表示不限制
   * @return 批处理结果
   * @throws SQLException 扫描或 rollback 失败时抛出
   */
  public AdbLockResolveBatchResult resolveExpiredLocks(long nowTs, int limit)
      throws SQLException {
    List<AdbTxnLock> locks = new AdbTxnLockScanner(store)
        .scanExpiredLocks(nowTs, limit);
    int rolledBack = 0;
    for (AdbTxnLock lock : locks) {
      if (resolveExpiredLock(lock, nowTs)
          == AdbLockResolveAction.ROLLED_BACK) {
        rolledBack++;
      }
    }
    return new AdbLockResolveBatchResult(locks.size(), rolledBack);
  }

  private void rollback(long txnId) throws SQLException {
    try {
      store.rollbackAsync(txnId).join();
    } catch (CompletionException e) {
      Throwable cause = unwrap(e);
      if (cause instanceof SQLException) {
        throw (SQLException) cause;
      }
      throw new SQLException("Failed to resolve expired ADB lock, txnId="
          + txnId, cause);
    } catch (RuntimeException e) {
      throw new SQLException("Failed to resolve expired ADB lock, txnId="
          + txnId, e);
    }
  }

  private static Throwable unwrap(Throwable error) {
    Throwable current = error;
    while (current instanceof CompletionException
        && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }
}
