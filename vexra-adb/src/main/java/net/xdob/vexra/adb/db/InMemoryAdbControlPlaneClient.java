package net.xdob.vexra.adb.db;

import net.xdob.vexra.cluster.region.RegionMetadata;
import net.xdob.vexra.cluster.txn.InMemoryTimestampOracle;
import net.xdob.vexra.cluster.txn.TimestampOracle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单进程内存 ADB 控制面客户端。
 *
 * <p>该实现用于测试和单机原型，提供可发布的 region 路由快照和内存 TSO。生产形态
 * 应替换为由 Raft/PD-like 服务持久化和复制的控制面。</p>
 */
public final class InMemoryAdbControlPlaneClient
    implements AdbRouteSnapshotPublisher {
  private final AtomicLong routeEpoch = new AtomicLong(0);
  private final AtomicReference<Collection<RegionMetadata>> regions;
  private final TimestampOracle timestampOracle;

  /**
   * 创建内存控制面客户端。
   *
   * @param regions 初始 region 元数据
   * @param initialTimestamp TSO 初始值
   */
  public InMemoryAdbControlPlaneClient(Collection<RegionMetadata> regions,
      long initialTimestamp) {
    this(regions, new InMemoryTimestampOracle(initialTimestamp));
  }

  /**
   * 创建内存控制面客户端。
   *
   * @param regions 初始 region 元数据
   * @param timestampOracle timestamp oracle
   */
  public InMemoryAdbControlPlaneClient(Collection<RegionMetadata> regions,
      TimestampOracle timestampOracle) {
    this.regions = new AtomicReference<>(copy(regions));
    this.timestampOracle = Objects.requireNonNull(timestampOracle,
        "timestampOracle == null");
    routeEpoch.set(1);
  }

  /**
   * 发布新的 region 元数据快照。
   *
   * @param newRegions 新 region 元数据
   * @return 发布后的 route epoch
   */
  public long publishRegions(Collection<RegionMetadata> newRegions) {
    regions.set(copy(newRegions));
    return routeEpoch.incrementAndGet();
  }

  @Override
  public AdbControlPlaneSnapshot getSnapshot() {
    return new AdbControlPlaneSnapshot(routeEpoch.get(), regions.get());
  }

  @Override
  public long nextTimestamp() {
    return timestampOracle.nextTimestamp();
  }

  private static Collection<RegionMetadata> copy(
      Collection<RegionMetadata> regions) {
    Objects.requireNonNull(regions, "regions == null");
    if (regions.isEmpty()) {
      throw new IllegalArgumentException("regions is empty");
    }
    return new ArrayList<>(regions);
  }
}
