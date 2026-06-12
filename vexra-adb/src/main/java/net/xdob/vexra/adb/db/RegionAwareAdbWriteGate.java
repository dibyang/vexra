package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.DataKey;
import net.xdob.vexra.cluster.region.RegionMetadata;
import net.xdob.vexra.cluster.region.RegionRouter;
import net.xdob.vexra.cluster.region.RegionWitnessBinding;

import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 基于 region 路由和 witness 多数派的 ADB 写入 gate。
 *
 * <p>该类把 ADB write set 中的 {@link DataKey} 路由到 region，再调用
 * {@link RegionWitnessBinding} 做多数派 fencing。它不负责网络探活和 ACK 收集，
 * 调用方需要通过 supplier 提供当前 leader 与已确认副本集合。</p>
 */
public final class RegionAwareAdbWriteGate implements AdbRegionWriteGate {
  private final RegionRouter router;
  private final RegionWitnessBinding witnessBinding;
  private final Supplier<String> leaderIdSupplier;
  private final Supplier<Collection<String>> acknowledgedReplicaIdsSupplier;

  /**
   * 创建 region-aware ADB 写入 gate。
   *
   * @param router region 路由快照
   * @param witnessBinding region witness 绑定入口
   * @param leaderIdSupplier 当前 leader 标识提供器
   * @param acknowledgedReplicaIdsSupplier 已确认副本集合提供器
   */
  public RegionAwareAdbWriteGate(RegionRouter router,
      RegionWitnessBinding witnessBinding, Supplier<String> leaderIdSupplier,
      Supplier<Collection<String>> acknowledgedReplicaIdsSupplier) {
    this.router = Objects.requireNonNull(router, "router == null");
    this.witnessBinding = Objects.requireNonNull(witnessBinding,
        "witnessBinding == null");
    this.leaderIdSupplier = Objects.requireNonNull(leaderIdSupplier,
        "leaderIdSupplier == null");
    this.acknowledgedReplicaIdsSupplier = Objects.requireNonNull(
        acknowledgedReplicaIdsSupplier, "acknowledgedReplicaIdsSupplier == null");
  }

  /**
   * 路由写入 key 并对每个命中的 region 执行一次多数派 fencing。
   *
   * @param txn 当前事务
   * @param writeKeys 当前事务写入的 ADB 数据 key 快照
   * @throws SQLException 当任一 region 不满足写入多数派时抛出
   */
  @Override
  public void beforeCommit(Transaction2 txn, Collection<DataKey> writeKeys)
      throws SQLException {
    if (writeKeys == null || writeKeys.isEmpty()) {
      return;
    }

    String leaderId = leaderIdSupplier.get();
    Collection<String> acknowledgedReplicaIds =
        acknowledgedReplicaIdsSupplier.get();
    if (acknowledgedReplicaIds == null) {
      acknowledgedReplicaIds = Collections.emptyList();
    }

    Set<String> fencedRegionIds = new LinkedHashSet<>();
    for (DataKey key : writeKeys) {
      if (key == null) {
        continue;
      }
      RegionMetadata region = route(key);
      if (fencedRegionIds.add(region.getRegionId())) {
        fence(region, leaderId, acknowledgedReplicaIds, txn);
      }
    }
  }

  private RegionMetadata route(DataKey key) throws SQLException {
    try {
      return router.route(key.toBytes());
    } catch (RuntimeException e) {
      throw new SQLException("ADB write key cannot be routed to region: " + key,
          e);
    }
  }

  private void fence(RegionMetadata region, String leaderId,
      Collection<String> acknowledgedReplicaIds, Transaction2 txn)
      throws SQLException {
    try {
      witnessBinding.fenceWrite(region, leaderId, acknowledgedReplicaIds);
    } catch (RuntimeException e) {
      throw new SQLException("ADB region write quorum is not satisfied, txnId="
          + txn.getTxnId() + ", regionId=" + region.getRegionId(), e);
    }
  }
}
