package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.DataKey;
import net.xdob.vexra.cluster.region.RegionMetadata;
import net.xdob.vexra.cluster.region.RegionRouter;

import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * ADB region commit 协调器。
 *
 * <p>该协调器位于 {@link TxnManager} durable commit 前，负责把事务 write set
 * 路由到 region，校验 leader/epoch，并把单 region 提交交给
 * {@link AdbRegionCommitClient}。当前阶段只允许单 region 提交；跨 region 事务由
 * 后续 2PC 阶段接管。</p>
 */
public final class AdbRegionCommitCoordinator {
  private final RegionRouter router;
  private final AdbRegionCommitClient client;

  /**
   * 创建 ADB region commit 协调器。
   *
   * @param router region 路由快照
   * @param client region commit client
   */
  public AdbRegionCommitCoordinator(RegionRouter router,
      AdbRegionCommitClient client) {
    this.router = Objects.requireNonNull(router, "router == null");
    this.client = Objects.requireNonNull(client, "client == null");
  }

  /**
   * 提交当前事务 write set 对应的单 region 写入。
   *
   * @param txn 当前事务
   * @param commitTs commit timestamp
   * @param writeKeys write set key 快照
   * @param metas 提交元数据
   * @return 提交完成 future
   */
  public CompletableFuture<Void> commitAsync(Transaction2 txn, long commitTs,
      Collection<DataKey> writeKeys, List<Meta> metas) {
    try {
      AdbRegionCommitRequest request = buildRequest(txn, commitTs, writeKeys,
          metas);
      CompletableFuture<Void> future = client.commitAsync(request);
      if (future != null) {
        return future;
      }
      return failed(new NullPointerException("commitAsync returned null"));
    } catch (RuntimeException e) {
      return failed(e);
    }
  }

  private AdbRegionCommitRequest buildRequest(Transaction2 txn, long commitTs,
      Collection<DataKey> writeKeys, List<Meta> metas) {
    Objects.requireNonNull(txn, "txn == null");
    Objects.requireNonNull(writeKeys, "writeKeys == null");
    if (writeKeys.isEmpty()) {
      throw new IllegalArgumentException("writeKeys is empty");
    }

    Set<RegionMetadata> regions = new LinkedHashSet<>();
    for (DataKey key : writeKeys) {
      regions.add(router.route(key.toBytes()));
    }
    if (regions.size() != 1) {
      throw new IllegalStateException(
          "ADB region commit requires single region, actual=" + regions.size());
    }

    RegionMetadata region = regions.iterator().next();
    String leaderId = region.getReplicaMetadata().getLeaderId();
    if (leaderId == null || leaderId.trim().isEmpty()) {
      throw new IllegalStateException("Region leader is empty, regionId="
          + region.getRegionId());
    }
    if (region.getEpoch() != region.getReplicaMetadata().getEpoch()) {
      throw new IllegalStateException("Region epoch mismatch, regionId="
          + region.getRegionId() + ", regionEpoch=" + region.getEpoch()
          + ", replicaEpoch=" + region.getReplicaMetadata().getEpoch());
    }

    return new AdbRegionCommitRequest(region.getRegionId(), region.getEpoch(),
        leaderId, txn.getTxnId(), commitTs, writeKeys, metas);
  }

  private static CompletableFuture<Void> failed(Throwable error) {
    CompletableFuture<Void> future = new CompletableFuture<>();
    future.completeExceptionally(error);
    return future;
  }
}
