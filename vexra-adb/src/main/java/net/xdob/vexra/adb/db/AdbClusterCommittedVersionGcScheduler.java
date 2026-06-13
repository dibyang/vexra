package net.xdob.vexra.adb.db;

import net.xdob.vexra.cluster.region.RegionMetadata;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * ADB 集群级 committed version GC 分片调度器。
 *
 * <p>该调度器读取控制面快照，为每个有 leader 的 region 生成 GC 请求，并通过
 * {@link AdbRegionCommittedVersionGcClient} 异步派发。它只定义调度、超时、
 * 失败映射和结果聚合语义，不实现真实传输、worker lease、leader fencing 或
 * region key range 删除。</p>
 */
public final class AdbClusterCommittedVersionGcScheduler {
  private final Supplier<AdbControlPlaneSnapshot> snapshotSupplier;
  private final LongSupplier safePointSupplier;
  private final AdbRegionCommittedVersionGcClient client;

  /**
   * 创建集群级 GC 分片调度器。
   *
   * @param controlPlaneClient 控制面客户端
   * @param safePointManager GC safe point manager
   * @param client region GC 异步客户端
   */
  public AdbClusterCommittedVersionGcScheduler(
      AdbControlPlaneClient controlPlaneClient,
      AdbGcSafePointManager safePointManager,
      AdbRegionCommittedVersionGcClient client) {
    this(() -> Objects.requireNonNull(controlPlaneClient,
        "controlPlaneClient == null").getSnapshot(),
        () -> Objects.requireNonNull(safePointManager,
            "safePointManager == null").getSafePoint(), client);
  }

  /**
   * 创建集群级 GC 分片调度器。
   *
   * @param snapshotSupplier 当前控制面快照提供器
   * @param safePointSupplier 当前 GC safe point 提供器
   * @param client region GC 异步客户端
   */
  public AdbClusterCommittedVersionGcScheduler(
      Supplier<AdbControlPlaneSnapshot> snapshotSupplier,
      LongSupplier safePointSupplier,
      AdbRegionCommittedVersionGcClient client) {
    this.snapshotSupplier = Objects.requireNonNull(snapshotSupplier,
        "snapshotSupplier == null");
    this.safePointSupplier = Objects.requireNonNull(safePointSupplier,
        "safePointSupplier == null");
    this.client = Objects.requireNonNull(client, "client == null");
  }

  /**
   * 执行一轮按 region 分片的 committed version GC 调度。
   *
   * @param limit 每个 region 单轮最多删除多少个历史版本，0 表示不限
   * @param timeoutMillis 整轮调度超时，0 表示不限
   * @return 集群级 GC 调度结果
   * @throws SQLException 任一 region 派发、执行、等待或超时失败时抛出
   */
  public AdbClusterCommittedVersionGcResult cleanOnce(int limit,
      long timeoutMillis) throws SQLException {
    if (limit < 0) {
      throw new IllegalArgumentException("limit is negative: " + limit);
    }
    if (timeoutMillis < 0) {
      throw new IllegalArgumentException(
          "timeoutMillis is negative: " + timeoutMillis);
    }
    AdbControlPlaneSnapshot snapshot = snapshotSupplier.get();
    if (snapshot == null) {
      throw new SQLException("ADB control-plane snapshot is null");
    }
    long safePoint = safePointSupplier.getAsLong();
    if (safePoint < 0) {
      throw new IllegalArgumentException("safePoint is negative: "
          + safePoint);
    }

    DispatchPlan plan = dispatch(snapshot, safePoint, limit, timeoutMillis);
    long deadline = timeoutMillis == 0 ? Long.MAX_VALUE
        : System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    try {
      return collect(snapshot.getRouteEpoch(), plan, deadline, timeoutMillis);
    } catch (SQLException e) {
      cancel(plan.futures);
      throw e;
    }
  }

  private DispatchPlan dispatch(AdbControlPlaneSnapshot snapshot,
      long safePoint, int limit, long timeoutMillis) {
    List<RegionFuture> futures = new ArrayList<>();
    int skippedNoLeader = 0;
    for (RegionMetadata region : snapshot.getRegions()) {
      String leaderId = region.getReplicaMetadata().getLeaderId();
      if (leaderId == null || leaderId.trim().isEmpty()) {
        skippedNoLeader++;
        continue;
      }
      AdbRegionCommittedVersionGcRequest request =
          new AdbRegionCommittedVersionGcRequest(region.getRegionId(),
              region.getEpoch(), leaderId, snapshot.getRouteEpoch(),
              region.getRange(), safePoint, limit, timeoutMillis);
      futures.add(new RegionFuture(region.getRegionId(), cleanAsync(request)));
    }
    return new DispatchPlan(futures, skippedNoLeader);
  }

  private CompletableFuture<AdbGcCleanResult> cleanAsync(
      AdbRegionCommittedVersionGcRequest request) {
    try {
      CompletableFuture<AdbGcCleanResult> future = client.cleanAsync(request);
      if (future != null) {
        return future;
      }
      CompletableFuture<AdbGcCleanResult> failed =
          new CompletableFuture<>();
      failed.completeExceptionally(new NullPointerException(
          "cleanAsync returned null"));
      return failed;
    } catch (RuntimeException e) {
      CompletableFuture<AdbGcCleanResult> failed =
          new CompletableFuture<>();
      failed.completeExceptionally(e);
      return failed;
    }
  }

  private AdbClusterCommittedVersionGcResult collect(long routeEpoch,
      DispatchPlan plan, long deadline, long timeoutMillis)
      throws SQLException {
    Map<String, AdbGcCleanResult> results = new LinkedHashMap<>();
    for (RegionFuture future : plan.futures) {
      results.put(future.regionId, await(future, deadline, timeoutMillis));
    }
    return new AdbClusterCommittedVersionGcResult(routeEpoch,
        plan.futures.size(), results.size(), plan.skippedNoLeader, results);
  }

  private static AdbGcCleanResult await(RegionFuture future, long deadline,
      long timeoutMillis) throws SQLException {
    try {
      if (timeoutMillis == 0) {
        return requireResult(future.regionId, future.future.get());
      }
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        future.future.cancel(true);
        throw timeout(future.regionId, timeoutMillis, null);
      }
      return requireResult(future.regionId,
          future.future.get(remaining, TimeUnit.NANOSECONDS));
    } catch (TimeoutException e) {
      future.future.cancel(true);
      throw timeout(future.regionId, timeoutMillis, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SQLException("Interrupted while executing region GC, regionId="
          + future.regionId, e);
    } catch (ExecutionException e) {
      throw failure(future.regionId, e.getCause());
    }
  }

  private static AdbGcCleanResult requireResult(String regionId,
      AdbGcCleanResult result) throws SQLException {
    if (result == null) {
      throw new SQLException("Region GC returned null, regionId="
          + regionId);
    }
    return result;
  }

  private static SQLException timeout(String regionId, long timeoutMillis,
      Throwable cause) {
    return new SQLException("Timed out executing region GC, regionId="
        + regionId + ", timeoutMillis=" + timeoutMillis, cause);
  }

  private static SQLException failure(String regionId, Throwable cause) {
    Throwable unwrapped = cause == null
        ? new RuntimeException("unknown failure")
        : cause;
    if (unwrapped instanceof SQLException) {
      return new SQLException("Region GC failed, regionId=" + regionId
          + ": " + unwrapped.getMessage(), unwrapped);
    }
    return new SQLException("Region GC failed, regionId=" + regionId,
        unwrapped);
  }

  private static void cancel(List<RegionFuture> futures) {
    for (RegionFuture future : futures) {
      future.future.cancel(true);
    }
  }

  private static final class DispatchPlan {
    private final List<RegionFuture> futures;
    private final int skippedNoLeader;

    private DispatchPlan(List<RegionFuture> futures, int skippedNoLeader) {
      this.futures = futures;
      this.skippedNoLeader = skippedNoLeader;
    }
  }

  private static final class RegionFuture {
    private final String regionId;
    private final CompletableFuture<AdbGcCleanResult> future;

    private RegionFuture(String regionId,
        CompletableFuture<AdbGcCleanResult> future) {
      this.regionId = regionId;
      this.future = Objects.requireNonNull(future, "future == null");
    }
  }
}
