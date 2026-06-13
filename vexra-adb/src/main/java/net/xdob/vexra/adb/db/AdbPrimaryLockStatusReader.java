package net.xdob.vexra.adb.db;

import java.sql.SQLException;

/**
 * ADB primary lock 状态读取器。
 *
 * <p>该接口把 lock resolver 与 primary 状态来源解耦。单机模式读取本地 store；
 * 分布式模式后续可以替换为 region-aware/RPC 实现来查询 primary 所在 region。</p>
 */
@FunctionalInterface
public interface AdbPrimaryLockStatusReader {
  /**
   * 查询指定 lock 对应 primary 的提交状态。
   *
   * @param lock secondary 或 primary lock 记录
   * @return primary 状态
   * @throws SQLException 查询失败时抛出
   */
  AdbPrimaryLockStatus readPrimaryStatus(AdbTxnLock lock)
      throws SQLException;
}
