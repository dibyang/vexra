package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.DataKey;

import java.sql.SQLException;

/**
 * ADB region 读路由入口。
 *
 * <p>该接口位于 ADB 点读/扫描入口和后续 region 执行层之间。默认实现为 no-op，
 * 保持单机 store 读取行为不变；分布式部署可以安装 region-aware 实现，在本地读取
 * 或后续远程执行前完成 region 路由和诊断记录。</p>
 */
public interface AdbRegionReadRouter {
  /**
   * 默认 no-op router，用于单机模式和未启用分布式 region 的兼容路径。
   */
  AdbRegionReadRouter NOOP = new AdbRegionReadRouter() {
  };

  /**
   * 路由一次点读。
   *
   * @param txn 当前事务
   * @param key 点读数据 key
   * @throws SQLException 当路由失败时抛出
   */
  default void routePointRead(Transaction2 txn, DataKey key)
      throws SQLException {
  }

  /**
   * 路由一次范围读。
   *
   * @param txn 当前事务
   * @param startKeyInclusive 起始 key，空数组表示无下界
   * @param endKeyExclusive 结束 key，空数组表示无上界
   * @throws SQLException 当路由失败时抛出
   */
  default void routeRangeRead(Transaction2 txn, byte[] startKeyInclusive,
      byte[] endKeyExclusive) throws SQLException {
  }
}
