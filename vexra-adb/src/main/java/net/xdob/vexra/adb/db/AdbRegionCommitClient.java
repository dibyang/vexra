package net.xdob.vexra.adb.db;

import java.util.concurrent.CompletableFuture;

/**
 * ADB region commit 异步客户端。
 *
 * <p>该接口隔离 ADB 事务提交路径与具体 region 写入实现。真实 Raft/RPC client、
 * 测试 fake 和本地 bridge 都通过同一接口执行 region commit。</p>
 */
@FunctionalInterface
public interface AdbRegionCommitClient {
  /**
   * 异步提交一个 region commit 请求。
   *
   * @param request region commit 请求
   * @return 提交完成 future
   */
  CompletableFuture<Void> commitAsync(AdbRegionCommitRequest request);
}
