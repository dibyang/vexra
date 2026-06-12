package net.xdob.vexra.adb.db;

import java.util.concurrent.CompletableFuture;

/**
 * ADB region 事务提交异步客户端。
 *
 * <p>该接口隔离 ADB 事务提交路径与具体 region 写入实现。真实 Raft/RPC client、
 * 测试 fake 和本地 bridge 都通过同一接口执行 region prewrite、commit 和 rollback。
 * 默认 prewrite/rollback 是兼容旧单 region commit bridge 的空实现；真实分布式
 * region client 必须覆盖这两个方法并保证幂等。</p>
 */
@FunctionalInterface
public interface AdbRegionCommitClient {
  /**
   * 异步预写一个 region 参与者。
   *
   * @param request region 事务请求
   * @return 预写完成 future
   */
  default CompletableFuture<Void> prewriteAsync(
      AdbRegionCommitRequest request) {
    return CompletableFuture.completedFuture(null);
  }

  /**
   * 异步提交一个 region commit 请求。
   *
   * @param request region commit 请求
   * @return 提交完成 future
   */
  CompletableFuture<Void> commitAsync(AdbRegionCommitRequest request);

  /**
   * 异步回滚一个 region 参与者。
   *
   * @param request region 事务请求
   * @return 回滚完成 future
   */
  default CompletableFuture<Void> rollbackAsync(
      AdbRegionCommitRequest request) {
    return CompletableFuture.completedFuture(null);
  }
}
