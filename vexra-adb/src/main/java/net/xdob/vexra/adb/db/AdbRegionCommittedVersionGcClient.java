package net.xdob.vexra.adb.db;

import java.util.concurrent.CompletableFuture;

/**
 * ADB region committed version GC 异步客户端。
 *
 * <p>该接口隔离集群级 GC 调度器和具体传输实现。真实部署中它可以映射到
 * Raft/RPC、进程内 bridge 或带 worker lease 的远程执行器；调度器只依赖
 * request/response 语义，不持有连接生命周期。</p>
 */
@FunctionalInterface
public interface AdbRegionCommittedVersionGcClient {

  /**
   * 异步执行一个 region 的 committed version GC 请求。
   *
   * @param request region GC 请求
   * @return 异步 GC 清理结果
   */
  CompletableFuture<AdbGcCleanResult> cleanAsync(
      AdbRegionCommittedVersionGcRequest request);
}
