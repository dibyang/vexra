package net.xdob.vexra.adb.db;

import net.xdob.vexra.cluster.region.RegionMetadata;
import net.xdob.vexra.cluster.region.RegionRouter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * ADB 控制面 region 路由快照。
 *
 * <p>快照携带 route epoch、region 元数据和不可变 {@link RegionRouter}。ADB session
 * 刷新快照后，新事务读写路由会使用新的 router。</p>
 */
public final class AdbControlPlaneSnapshot {
  private final long routeEpoch;
  private final List<RegionMetadata> regions;
  private final RegionRouter router;

  /**
   * 创建控制面快照。
   *
   * @param routeEpoch 路由快照 epoch
   * @param regions region 元数据集合
   */
  public AdbControlPlaneSnapshot(long routeEpoch,
      Collection<RegionMetadata> regions) {
    if (routeEpoch < 0) {
      throw new IllegalArgumentException("routeEpoch is negative: "
          + routeEpoch);
    }
    Objects.requireNonNull(regions, "regions == null");
    if (regions.isEmpty()) {
      throw new IllegalArgumentException("regions is empty");
    }
    this.routeEpoch = routeEpoch;
    this.regions = Collections.unmodifiableList(new ArrayList<>(regions));
    this.router = new RegionRouter(this.regions);
  }

  public long getRouteEpoch() {
    return routeEpoch;
  }

  public List<RegionMetadata> getRegions() {
    return regions;
  }

  public RegionRouter getRouter() {
    return router;
  }
}
