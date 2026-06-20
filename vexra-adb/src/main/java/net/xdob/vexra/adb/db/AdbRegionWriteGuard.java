package net.xdob.vexra.adb.db;

import java.sql.SQLException;

/**
 * ADB region commit 前的写入保护钩子。
 *
 * <p>该钩子允许 runtime 在 region commit coordinator 真正路由和提交前检查
 * 控制面 TTL、租约或发布门禁。默认 no-op 保持既有构造器兼容。</p>
 */
@FunctionalInterface
public interface AdbRegionWriteGuard {
  /** 不执行任何检查的默认写入保护。 */
  AdbRegionWriteGuard NOOP = () -> {
  };

  /**
   * 在 region commit 前执行检查。
   *
   * @throws SQLException 检查失败时抛出，提交会失败并回到调用方
   */
  void beforeCommit() throws SQLException;
}
