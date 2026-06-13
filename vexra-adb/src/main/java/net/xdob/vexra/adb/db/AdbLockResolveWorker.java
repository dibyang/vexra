package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.DbStore;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * ADB 后台 lock resolve worker。
 *
 * <p>该 worker 是 ADB-Prod-02 的调度入口，负责周期触发
 * {@link AdbLockResolver#resolveExpiredLocks(long, int)}。它不拥有新的事务语义，
 * 只复用 resolver 已实现的过期 rollback 与 primary committed 前滚路径。</p>
 */
public final class AdbLockResolveWorker implements AutoCloseable {
  private final AdbLockResolver resolver;
  private final LongSupplier nowSupplier;
  private final int limit;
  private final long intervalMillis;
  private final ScheduledExecutorService executor;
  private final AtomicBoolean started = new AtomicBoolean();
  private volatile ScheduledFuture<?> future;
  private volatile AdbLockResolveBatchResult lastResult;
  private volatile SQLException lastFailure;

  /**
   * 创建 lock resolve worker。
   *
   * @param store ADB store
   * @param nowSupplier 当前时间戳提供器
   * @param limit 每轮最多处理多少条，0 表示不限制
   * @param intervalMillis 周期调度间隔，必须大于 0
   */
  public AdbLockResolveWorker(DbStore store, LongSupplier nowSupplier,
      int limit, long intervalMillis) {
    this(new AdbLockResolver(Objects.requireNonNull(store, "store == null")),
        nowSupplier, limit, intervalMillis);
  }

  /**
   * 创建 lock resolve worker。
   *
   * @param resolver lock resolver
   * @param nowSupplier 当前时间戳提供器
   * @param limit 每轮最多处理多少条，0 表示不限制
   * @param intervalMillis 周期调度间隔，必须大于 0
   */
  public AdbLockResolveWorker(AdbLockResolver resolver,
      LongSupplier nowSupplier, int limit, long intervalMillis) {
    this.resolver = Objects.requireNonNull(resolver, "resolver == null");
    this.nowSupplier = Objects.requireNonNull(nowSupplier,
        "nowSupplier == null");
    if (limit < 0) {
      throw new IllegalArgumentException("limit is negative: " + limit);
    }
    if (intervalMillis <= 0) {
      throw new IllegalArgumentException("intervalMillis must be positive");
    }
    this.limit = limit;
    this.intervalMillis = intervalMillis;
    this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread thread = new Thread(r, "adb-lock-resolve-worker");
      thread.setDaemon(true);
      return thread;
    });
  }

  /**
   * 启动周期 resolve，重复调用保持幂等。
   */
  public void start() {
    if (started.compareAndSet(false, true)) {
      future = executor.scheduleWithFixedDelay(this::resolveQuietly, 0,
          intervalMillis, TimeUnit.MILLISECONDS);
    }
  }

  /**
   * 立即执行一轮 resolve。
   *
   * @return 本轮批处理结果
   * @throws SQLException resolve 失败时抛出
   */
  public AdbLockResolveBatchResult resolveOnce() throws SQLException {
    AdbLockResolveBatchResult result = resolver.resolveExpiredLocks(
        nowSupplier.getAsLong(), limit);
    lastResult = result;
    lastFailure = null;
    return result;
  }

  /**
   * 返回 worker 是否已经启动。
   *
   * @return 已启动返回 true
   */
  public boolean isStarted() {
    return started.get();
  }

  /**
   * 返回最近一次成功批处理结果。
   *
   * @return 最近一次结果，不存在时为空
   */
  public Optional<AdbLockResolveBatchResult> getLastResult() {
    return Optional.ofNullable(lastResult);
  }

  /**
   * 返回最近一次失败。
   *
   * @return 最近一次失败，不存在时为空
   */
  public Optional<SQLException> getLastFailure() {
    return Optional.ofNullable(lastFailure);
  }

  /**
   * 关闭后台调度，重复调用保持幂等。
   */
  @Override
  public void close() {
    ScheduledFuture<?> current = future;
    if (current != null) {
      current.cancel(true);
    }
    executor.shutdownNow();
  }

  private void resolveQuietly() {
    try {
      resolveOnce();
    } catch (SQLException e) {
      lastFailure = e;
    }
  }
}
