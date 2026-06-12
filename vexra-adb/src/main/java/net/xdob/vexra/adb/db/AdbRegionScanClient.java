package net.xdob.vexra.adb.db;

import net.xdob.vexra.cluster.sql.RegionQueryResult;

import java.util.concurrent.CompletableFuture;

/**
 * ADB region scan 异步客户端。
 *
 * <p>该接口隔离分布式执行器与具体传输实现。真实网络 RPC、测试 fake 和本地 bridge
 * 都通过同一接口返回 region 查询结果。</p>
 */
@FunctionalInterface
public interface AdbRegionScanClient {
  /**
   * 异步执行一个 region scan 请求。
   *
   * @param request region scan 请求
   * @return 异步 region 查询结果
   */
  CompletableFuture<RegionQueryResult> scanAsync(AdbRegionScanRequest request);
}
