package net.xdob.vexra.adb.db;

import java.sql.SQLException;
import java.util.Collection;
import java.util.Objects;

/**
 * ADB 跨 region 事务生产边界 guard。
 *
 * <p>该 guard 把 region commit coordinator 得到的参与 region 列表交给
 * {@link AdbProductionGuard}。在 MVP 生产模式下，单 region 事务会按
 * `SINGLE_REGION_TRANSACTION` 能力校验，跨 region 事务默认走
 * `CROSS_REGION_TRANSACTION` 并被拒绝；实验模式显式开启后才允许进入 2PC。</p>
 */
public final class AdbCrossRegionTxnGuard {
  private static final AdbCrossRegionTxnGuard NOOP =
      new AdbCrossRegionTxnGuard(null);

  private final AdbProductionGuard productionGuard;

  private AdbCrossRegionTxnGuard(AdbProductionGuard productionGuard) {
    this.productionGuard = productionGuard;
  }

  /**
   * 创建不执行生产能力校验的 guard。
   *
   * @return no-op guard
   */
  public static AdbCrossRegionTxnGuard noop() {
    return NOOP;
  }

  /**
   * 基于生产范围 guard 创建事务 region guard。
   *
   * @param productionGuard 生产范围 guard
   * @return 跨 region 事务 guard
   */
  public static AdbCrossRegionTxnGuard fromProductionGuard(
      AdbProductionGuard productionGuard) {
    return new AdbCrossRegionTxnGuard(Objects.requireNonNull(productionGuard,
        "productionGuard == null"));
  }

  /**
   * 在 region commit 前校验事务命中的 region 数量。
   *
   * @param requestName 请求名称
   * @param routeEpoch 请求使用的 route epoch
   * @param regionIds 事务命中的 region id 集合
   * @throws SQLException 生产 guard 拒绝能力时抛出
   */
  public void beforeCommit(String requestName, long routeEpoch,
      Collection<String> regionIds) throws SQLException {
    if (productionGuard == null) {
      return;
    }
    productionGuard.validateTransactionRegions(regionIds,
        new AdbProductionRequestContext(requestName, routeEpoch, regionIds));
  }
}
