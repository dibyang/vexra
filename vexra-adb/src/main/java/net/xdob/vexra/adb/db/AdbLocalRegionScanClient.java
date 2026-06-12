package net.xdob.vexra.adb.db;

import net.xdob.vexra.cluster.sql.RegionQueryResult;

import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * ADB 本地 region scan client。
 *
 * <p>该实现把远程 scan client 接口桥接到本地 {@link AdbLocalRegionScanExecutor}，
 * 便于单机模式、测试和后续真实 RPC 上线前的兼容执行。</p>
 */
public final class AdbLocalRegionScanClient implements AdbRegionScanClient {
  private final AdbLocalRegionScanExecutor executor;

  /**
   * 创建本地 bridge client。
   *
   * @param executor ADB 本地 region scan 执行器
   */
  public AdbLocalRegionScanClient(AdbLocalRegionScanExecutor executor) {
    this.executor = Objects.requireNonNull(executor, "executor == null");
  }

  /**
   * 异步执行请求，异常会通过 CompletableFuture 传递给上层 executor。
   *
   * @param request region scan 请求
   * @return 异步 region 查询结果
   */
  @Override
  public CompletableFuture<RegionQueryResult> scanAsync(
      AdbRegionScanRequest request) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        Transaction2 txn = request.toReadOnlyTransaction();
        if (request.isCountOnly()) {
          return executor.executeCount(txn, request.getTask());
        }
        return executor.execute(txn, request.getTask());
      } catch (SQLException e) {
        throw new RegionScanClientException(e);
      }
    });
  }

  private static final class RegionScanClientException extends RuntimeException {
    private RegionScanClientException(SQLException cause) {
      super(cause);
    }
  }
}
