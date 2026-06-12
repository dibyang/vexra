package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.DataKey;

import java.sql.SQLException;
import java.util.Collection;

/**
 * ADB region 写入 gate。
 *
 * <p>该接口位于 ADB 事务提交路径和后续 region/复制层之间。默认实现为 no-op，
 * 保持单机 ADB 与 H2 插件模式行为不变；分布式部署可以安装 region-aware 实现，
 * 在 durable commit 前执行路由、leader、witness 多数派或 lease 检查。</p>
 */
@FunctionalInterface
public interface AdbRegionWriteGate {
  /**
   * 默认 no-op gate，用于单机模式和未启用分布式 region 的兼容路径。
   */
  AdbRegionWriteGate NOOP = (txn, writeKeys) -> {
  };

  /**
   * 在事务进入 durable commit 前检查 write set 是否允许写入。
   *
   * @param txn 当前事务
   * @param writeKeys 当前事务写入的 ADB 数据 key 快照
   * @throws SQLException 当 region 路由、fencing 或多数派检查失败时抛出
   */
  void beforeCommit(Transaction2 txn, Collection<DataKey> writeKeys)
      throws SQLException;
}
