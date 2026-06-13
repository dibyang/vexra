package net.xdob.vexra.adb.db;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * ADB 带本地租约保护的全局 safe point 推进器。
 *
 * <p>该推进器把 {@link AdbGlobalSafePointAdvancer} 和
 * {@link AdbSafePointLeaseStore} 组合起来：每轮先抢占或续租本地 safe point
 * lease，只有租约持有者才允许推进并持久化 safe point。它用于约束单 store 内
 * 多个 GC worker 的并发推进；真正跨进程、跨节点的线性一致 lease 仍应由后续
 * PD/etcd 或控制面复制实现替换。</p>
 */
public final class AdbLeasedGlobalSafePointAdvancer {
  private final AdbSafePointLeaseStore leaseStore;
  private final AdbGlobalSafePointAdvancer delegate;
  private final String ownerId;
  private final LongSupplier nowMillisSupplier;
  private final long leaseMillis;

  /**
   * 创建带租约保护的 safe point 推进器。
   *
   * @param leaseStore safe point lease 持久化 store
   * @param delegate 进程内 safe point 推进器
   * @param ownerId 当前 worker 的 lease owner 标识
   * @param nowMillisSupplier 当前毫秒时间戳提供器
   * @param leaseMillis lease 时长，必须大于 0
   */
  public AdbLeasedGlobalSafePointAdvancer(
      AdbSafePointLeaseStore leaseStore,
      AdbGlobalSafePointAdvancer delegate, String ownerId,
      LongSupplier nowMillisSupplier, long leaseMillis) {
    this.leaseStore = Objects.requireNonNull(leaseStore,
        "leaseStore == null");
    this.delegate = Objects.requireNonNull(delegate, "delegate == null");
    this.ownerId = normalizeOwner(ownerId);
    this.nowMillisSupplier = Objects.requireNonNull(nowMillisSupplier,
        "nowMillisSupplier == null");
    if (leaseMillis <= 0) {
      throw new IllegalArgumentException("leaseMillis must be positive");
    }
    this.leaseMillis = leaseMillis;
  }

  /**
   * 执行一轮带租约保护的 safe point 推进。
   *
   * @return 本轮租约与 safe point 推进结果
   * @throws SQLException 租约读取、续租或 safe point 持久化失败时抛出
   */
  public AdbLeasedGlobalSafePointAdvanceResult advanceOnce()
      throws SQLException {
    long nowMillis = nonNegative(nowMillisSupplier.getAsLong(),
        "nowMillis");
    Optional<AdbSafePointLeaseRecord> acquired =
        leaseStore.tryAcquire(ownerId, nowMillis, leaseMillis);
    if (!acquired.isPresent()) {
      return new AdbLeasedGlobalSafePointAdvanceResult(false,
          leaseStore.read(), null);
    }

    AdbGlobalSafePointAdvanceResult advanceResult = delegate.advanceOnce();
    long persistedSafePoint = Math.max(acquired.get().getSafePoint(),
        advanceResult.getSafePoint());
    AdbSafePointLeaseRecord persisted = leaseStore.advanceSafePoint(ownerId,
        persistedSafePoint, nowMillis);
    return new AdbLeasedGlobalSafePointAdvanceResult(true, persisted,
        advanceResult);
  }

  private static String normalizeOwner(String ownerId) {
    if (ownerId == null || ownerId.trim().isEmpty()) {
      throw new IllegalArgumentException("ownerId is empty");
    }
    return ownerId.trim();
  }

  private static long nonNegative(long value, String fieldName) {
    if (value < 0) {
      throw new IllegalArgumentException(fieldName + " is negative: "
          + value);
    }
    return value;
  }
}
