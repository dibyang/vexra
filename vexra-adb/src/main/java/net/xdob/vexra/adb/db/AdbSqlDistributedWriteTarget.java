package net.xdob.vexra.adb.db;

import java.util.Objects;

/**
 * SQL 分布式写入目标。
 *
 * <p>该对象封装远端 commit client 和扩展模块持有的连接资源，让核心写入 runtime
 * 不需要 import 具体 Raft/RPC 类型。</p>
 */
public final class AdbSqlDistributedWriteTarget {
  private final AdbRpcRegionCommitClient commitClient;
  private final AutoCloseable closeable;

  /**
   * 创建写入目标。
   *
   * @param commitClient region commit client
   * @param closeable 扩展资源关闭句柄
   */
  public AdbSqlDistributedWriteTarget(
      AdbRpcRegionCommitClient commitClient, AutoCloseable closeable) {
    this.commitClient = Objects.requireNonNull(commitClient,
        "commitClient == null");
    this.closeable = Objects.requireNonNull(closeable, "closeable == null");
  }

  /**
   * 返回 region commit client。
   *
   * @return region commit client
   */
  public AdbRpcRegionCommitClient commitClient() {
    return commitClient;
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
