package net.xdob.vexra.adb.db;

import java.util.concurrent.CompletableFuture;

/**
 * ADB region commit 传输接口。
 *
 * <p>该接口是 ADB-Prod-01 的真实 Raft/RPC 替换边界。生产实现可以基于现有
 * Vexra Raft client、gRPC 或进程内测试 fake 发送 region 事务阶段请求；上层
 * {@link AdbRpcRegionCommitClient} 只依赖该抽象处理响应、异常和超时。</p>
 */
@FunctionalInterface
public interface AdbRegionCommitTransport {
  /**
   * 发送 region commit 阶段请求。
   *
   * @param phase commit 阶段
   * @param request region commit 请求
   * @return 异步响应
   */
  CompletableFuture<AdbRegionCommitResponse> sendAsync(
      AdbRegionCommitPhase phase, AdbRegionCommitRequest request);
}
