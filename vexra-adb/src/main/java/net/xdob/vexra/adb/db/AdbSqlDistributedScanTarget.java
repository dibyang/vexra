package net.xdob.vexra.adb.db;

import java.util.Objects;

/**
 * SQL 分布式 scan 目标。
 *
 * <p>该对象把核心执行器和扩展模块持有的连接资源放在一起。核心 ADB 只关闭
 * {@link #closeable()}，不需要知道底层是 Raft client、RPC client 还是其他实现。</p>
 */
public final class AdbSqlDistributedScanTarget {
  private final AdbDistributedRegionScanExecutor executor;
  private final AutoCloseable closeable;

  /**
   * 创建 scan 目标。
   *
   * @param executor region scan 执行器
   * @param closeable 扩展资源关闭句柄
   */
  public AdbSqlDistributedScanTarget(
      AdbDistributedRegionScanExecutor executor, AutoCloseable closeable) {
    this.executor = Objects.requireNonNull(executor, "executor == null");
    this.closeable = Objects.requireNonNull(closeable, "closeable == null");
  }

  /**
   * 返回 region scan 执行器。
   *
   * @return region scan 执行器
   */
  public AdbDistributedRegionScanExecutor executor() {
    return executor;
  }

  /**
   * 返回扩展资源关闭句柄。
   *
   * @return 扩展资源关闭句柄
   */
  public AutoCloseable closeable() {
    return closeable;
  }
}
