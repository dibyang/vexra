package net.xdob.vexra.adb.db;

import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * ADB 本地 region committed version GC 客户端。
 *
 * <p>该 adapter 将集群级调度器派发的
 * {@link AdbRegionCommittedVersionGcRequest} 直接转交给本地
 * {@link AdbCommittedVersionGcCleaner}。它适用于单进程测试、进程内部署和后续
 * 真实 RPC server 收到 region GC 请求后的服务端执行路径。</p>
 */
public final class AdbLocalRegionCommittedVersionGcClient
    implements AdbRegionCommittedVersionGcClient {
  private final AdbCommittedVersionGcCleaner cleaner;

  /**
   * 创建本地 region GC 客户端。
   *
   * @param cleaner committed version GC cleaner
   */
  public AdbLocalRegionCommittedVersionGcClient(
      AdbCommittedVersionGcCleaner cleaner) {
    this.cleaner = Objects.requireNonNull(cleaner, "cleaner == null");
  }

  /**
   * 异步执行 region GC 请求。
   *
   * @param request region GC 请求
   * @return 已完成或失败的异步清理结果
   */
  @Override
  public CompletableFuture<AdbGcCleanResult> cleanAsync(
      AdbRegionCommittedVersionGcRequest request) {
    CompletableFuture<AdbGcCleanResult> future = new CompletableFuture<>();
    try {
      future.complete(cleaner.cleanOnce(request));
    } catch (SQLException | RuntimeException e) {
      future.completeExceptionally(e);
    }
    return future;
  }
}
