package net.xdob.vexra.adb.db;

import net.xdob.vexra.cluster.sql.DistributedPlan;
import net.xdob.vexra.cluster.sql.DistributedResultMerger;
import net.xdob.vexra.cluster.sql.RegionQueryResult;
import net.xdob.vexra.cluster.sql.RegionScanTask;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * ADB 分布式 region scan 执行器。
 *
 * <p>该执行器负责把 {@link DistributedPlan} 中的多个 region scan task 并发派发给
 * {@link AdbRegionScanClient}，并把远程异常、超时和中断统一映射为
 * {@link SQLException}。它不关心底层是本地 bridge、进程内 fake 还是真实 RPC。</p>
 */
public final class AdbDistributedRegionScanExecutor {
  private final AdbRegionScanClient client;
  private final DistributedResultMerger merger;

  /**
   * 创建分布式 region scan 执行器。
   *
   * @param client region scan 客户端
   */
  public AdbDistributedRegionScanExecutor(AdbRegionScanClient client) {
    this(client, new DistributedResultMerger());
  }

  /**
   * 创建分布式 region scan 执行器。
   *
   * @param client region scan 客户端
   * @param merger 查询结果合并器
   */
  public AdbDistributedRegionScanExecutor(AdbRegionScanClient client,
      DistributedResultMerger merger) {
    this.client = Objects.requireNonNull(client, "client == null");
    this.merger = Objects.requireNonNull(merger, "merger == null");
  }

  /**
   * 执行多 region 行扫描并合并结果。
   *
   * @param txn 当前事务读视图
   * @param plan 分布式执行计划，不能是 count-only
   * @param timeoutMillis 整体超时时间，0 表示不限制
   * @return 合并后的行集合
   * @throws SQLException 当远程执行失败、超时或计划类型错误时抛出
   */
  public List<Map<String, Object>> executeRows(Transaction2 txn,
      DistributedPlan plan, long timeoutMillis) throws SQLException {
    if (plan.isCountOnly()) {
      throw new SQLException("Distributed plan is count-only");
    }
    return merger.mergeRows(executeTasks(txn, plan, timeoutMillis));
  }

  /**
   * 执行多 region count-only 扫描并合并 count。
   *
   * @param txn 当前事务读视图
   * @param plan 分布式执行计划，必须是 count-only
   * @param timeoutMillis 整体超时时间，0 表示不限制
   * @return 合并后的 count
   * @throws SQLException 当远程执行失败、超时或计划类型错误时抛出
   */
  public long executeCount(Transaction2 txn, DistributedPlan plan,
      long timeoutMillis) throws SQLException {
    if (!plan.isCountOnly()) {
      throw new SQLException("Distributed plan is not count-only");
    }
    return merger.mergeCount(executeTasks(txn, plan, timeoutMillis));
  }

  private List<RegionQueryResult> executeTasks(Transaction2 txn,
      DistributedPlan plan, long timeoutMillis) throws SQLException {
    Objects.requireNonNull(txn, "txn == null");
    Objects.requireNonNull(plan, "plan == null");
    if (timeoutMillis < 0) {
      throw new IllegalArgumentException(
          "timeoutMillis is negative: " + timeoutMillis);
    }

    List<RegionFuture> futures = new ArrayList<>();
    for (RegionScanTask task : plan.getTasks()) {
      AdbRegionScanRequest request = new AdbRegionScanRequest(task,
          txn.getTxnId(), txn.getStartTs(), plan.isCountOnly(), timeoutMillis);
      futures.add(new RegionFuture(task.getRegionId(), scanAsync(request)));
    }

    long deadline = timeoutMillis == 0 ? Long.MAX_VALUE
        : System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    List<RegionQueryResult> results = new ArrayList<>();
    for (RegionFuture future : futures) {
      results.add(await(future, deadline, timeoutMillis));
    }
    return results;
  }

  private CompletableFuture<RegionQueryResult> scanAsync(
      AdbRegionScanRequest request) {
    try {
      CompletableFuture<RegionQueryResult> future = client.scanAsync(request);
      if (future != null) {
        return future;
      }
      CompletableFuture<RegionQueryResult> failed = new CompletableFuture<>();
      failed.completeExceptionally(new NullPointerException(
          "scanAsync returned null"));
      return failed;
    } catch (RuntimeException e) {
      CompletableFuture<RegionQueryResult> failed = new CompletableFuture<>();
      failed.completeExceptionally(e);
      return failed;
    }
  }

  private static RegionQueryResult await(RegionFuture future, long deadline,
      long timeoutMillis) throws SQLException {
    try {
      if (timeoutMillis == 0) {
        return future.future.get();
      }
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        future.future.cancel(true);
        throw timeout(future.regionId, timeoutMillis, null);
      }
      return future.future.get(remaining, TimeUnit.NANOSECONDS);
    } catch (TimeoutException e) {
      future.future.cancel(true);
      throw timeout(future.regionId, timeoutMillis, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SQLException("Interrupted while executing remote region scan, regionId="
          + future.regionId, e);
    } catch (ExecutionException e) {
      throw failure(future.regionId, e.getCause());
    }
  }

  private static SQLException timeout(String regionId, long timeoutMillis,
      Throwable cause) {
    return new SQLException("Timed out executing remote region scan, regionId="
        + regionId + ", timeoutMillis=" + timeoutMillis, cause);
  }

  private static SQLException failure(String regionId, Throwable cause) {
    Throwable unwrapped = unwrap(cause);
    if (unwrapped instanceof SQLException) {
      return new SQLException("Remote region scan failed, regionId="
          + regionId + ": " + unwrapped.getMessage(), unwrapped);
    }
    return new SQLException("Remote region scan failed, regionId=" + regionId,
        unwrapped);
  }

  private static Throwable unwrap(Throwable cause) {
    Throwable current = cause;
    while (current instanceof RuntimeException && current.getCause() != null
        && current.getClass().getName().contains("RegionScanClientException")) {
      current = current.getCause();
    }
    return current == null ? new RuntimeException("unknown failure") : current;
  }

  private static final class RegionFuture {
    private final String regionId;
    private final CompletableFuture<RegionQueryResult> future;

    private RegionFuture(String regionId,
        CompletableFuture<RegionQueryResult> future) {
      this.regionId = regionId;
      this.future = Objects.requireNonNull(future, "future == null");
    }
  }
}
