package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.DataKey;
import net.xdob.vexra.cluster.region.KeyRange;
import net.xdob.vexra.cluster.region.RegionMetadata;
import net.xdob.vexra.cluster.region.RegionRouter;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 基于 region 元数据的 ADB 读路由器。
 *
 * <p>该类只负责把 ADB 点读和扫描范围映射到 region，并把结果交给可选观察器。
 * 它不负责远程 RPC、scan task 执行或结果合并，后续分布式 executor 可以复用这个
 * 稳定入口替换当前本地 store 读取。</p>
 */
public final class RegionAwareAdbReadRouter implements AdbRegionReadRouter {
  private final RegionRouter router;
  private final ReadRouteObserver observer;

  /**
   * 创建 region-aware ADB 读路由器。
   *
   * @param router region 路由快照
   */
  public RegionAwareAdbReadRouter(RegionRouter router) {
    this(router, ReadRouteObserver.NOOP);
  }

  /**
   * 创建 region-aware ADB 读路由器。
   *
   * @param router region 路由快照
   * @param observer 路由结果观察器
   */
  public RegionAwareAdbReadRouter(RegionRouter router,
      ReadRouteObserver observer) {
    this.router = Objects.requireNonNull(router, "router == null");
    this.observer = observer == null ? ReadRouteObserver.NOOP : observer;
  }

  /**
   * 将点读 key 路由到单个 region。
   *
   * @param txn 当前事务
   * @param key 点读数据 key
   * @throws SQLException 当没有 region 覆盖该 key 时抛出
   */
  @Override
  public void routePointRead(Transaction2 txn, DataKey key)
      throws SQLException {
    Objects.requireNonNull(key, "key == null");
    try {
      observer.onRoute(txn, ReadRouteKind.POINT,
          Collections.singletonList(router.route(key.toBytes())));
    } catch (RuntimeException e) {
      throw new SQLException("ADB read key cannot be routed to region: " + key,
          e);
    }
  }

  /**
   * 将范围读路由到所有相交 region。
   *
   * @param txn 当前事务
   * @param startKeyInclusive 起始 key，空数组表示无下界
   * @param endKeyExclusive 结束 key，空数组表示无上界
   * @throws SQLException 当范围没有命中任何 region 时抛出
   */
  @Override
  public void routeRangeRead(Transaction2 txn, byte[] startKeyInclusive,
      byte[] endKeyExclusive) throws SQLException {
    try {
      List<RegionMetadata> regions = router.route(
          new KeyRange(startKeyInclusive, endKeyExclusive));
      if (regions.isEmpty()) {
        throw new IllegalArgumentException("no region overlaps read range");
      }
      observer.onRoute(txn, ReadRouteKind.RANGE,
          Collections.unmodifiableList(new ArrayList<>(regions)));
    } catch (RuntimeException e) {
      throw new SQLException("ADB read range cannot be routed to region", e);
    }
  }

  /**
   * ADB 读路由类型。
   */
  public enum ReadRouteKind {
    /** 点读。 */
    POINT,
    /** 范围读。 */
    RANGE
  }

  /**
   * 读路由观察器。
   *
   * <p>观察器用于测试、诊断或 metrics，不应在回调中修改事务或执行阻塞式远程读。</p>
   */
  @FunctionalInterface
  public interface ReadRouteObserver {
    /**
     * 默认 no-op 观察器。
     */
    ReadRouteObserver NOOP = (txn, kind, regions) -> {
    };

    /**
     * 接收一次读路由结果。
     *
     * @param txn 当前事务
     * @param kind 读路由类型
     * @param regions 命中的 region 列表
     */
    void onRoute(Transaction2 txn, ReadRouteKind kind,
        List<RegionMetadata> regions);
  }
}
