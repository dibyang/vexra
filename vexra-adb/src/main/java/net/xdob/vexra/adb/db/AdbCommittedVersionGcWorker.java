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

/**
 * ADB 后台 committed version GC worker。
 *
 * <p>该 worker 是 ADB-Prod-02 的历史版本清理调度入口，负责周期触发
 * {@link AdbCommittedVersionGcCleaner#cleanOnce(int)}。它不推进全局 safe point，
 * 不参与跨 region 分片调度，只复用 cleaner 已实现的本地 committed version 删除语义。</p>
 */
public final class AdbCommittedVersionGcWorker implements AutoCloseable {
  private final AdbCommittedVersionGcCleaner cleaner;
  private final int limit;
  private final long intervalMillis;
  private final ScheduledExecutorService executor;
  private final AtomicBoolean started = new AtomicBoolean();
  private volatile ScheduledFuture<?> future;
  private volatile AdbGcCleanResult lastResult;
  private volatile SQLException lastFailure;

  /**
   * 创建 committed version GC worker。
   *
   * @param store ADB store
   * @param safePointManager GC safe point manager
   * @param limit 每轮最多删除多少个历史版本，0 表示不限制
   * @param intervalMillis 周期调度间隔，必须大于 0
   */
  public AdbCommittedVersionGcWorker(DbStore store,
      AdbGcSafePointManager safePointManager, int limit,
      long intervalMillis) {
    this(new AdbCommittedVersionGcCleaner(
        Objects.requireNonNull(store, "store == null"),
        Objects.requireNonNull(safePointManager,
            "safePointManager == null")), limit, intervalMillis);
  }

  /**
   * 创建 committed version GC worker。
   *
   * @param cleaner committed version GC cleaner
   * @param limit 每轮最多删除多少个历史版本，0 表示不限制
   * @param intervalMillis 周期调度间隔，必须大于 0
   */
  public AdbCommittedVersionGcWorker(AdbCommittedVersionGcCleaner cleaner,
      int limit, long intervalMillis) {
    this.cleaner = Objects.requireNonNull(cleaner, "cleaner == null");
    if (limit < 0) {
      throw new IllegalArgumentException("limit is negative: " + limit);
    }
    if (intervalMillis <= 0) {
      throw new IllegalArgumentException("intervalMillis must be positive");
    }
    this.limit = limit;
    this.intervalMillis = intervalMillis;
    this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread thread = new Thread(r, "adb-committed-version-gc-worker");
      thread.setDaemon(true);
      return thread;
    });
  }

  /**
   * 启动周期 GC，重复调用保持幂等。
   */
  public void start() {
    if (started.compareAndSet(false, true)) {
      future = executor.scheduleWithFixedDelay(this::cleanQuietly, 0,
          intervalMillis, TimeUnit.MILLISECONDS);
    }
  }

  /**
   * 立即执行一轮 committed version GC。
   *
   * @return 本轮 GC 结果
   * @throws SQLException GC 扫描或删除失败时抛出
   */
  public AdbGcCleanResult cleanOnce() throws SQLException {
    try {
      AdbGcCleanResult result = cleaner.cleanOnce(limit);
      lastResult = result;
      lastFailure = null;
      return result;
    } catch (SQLException e) {
      lastFailure = e;
      throw e;
    }
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
   * 返回最近一次成功 GC 的结果。
   *
   * @return 最近一次结果，不存在时为空
   */
  public Optional<AdbGcCleanResult> getLastResult() {
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

  private void cleanQuietly() {
    try {
      cleanOnce();
    } catch (SQLException e) {
      lastFailure = e;
    }
  }
}
