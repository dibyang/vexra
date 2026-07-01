package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.DbStore;

import java.sql.SQLException;

/**
 * ADB SQL 分布式运行时扩展点。
 *
 * <p>核心 ADB 只定义 region 读写语义，不直接依赖具体集群协议。Raft、RPC 或未来其他
 * 集群实现可以通过 ServiceLoader 提供该扩展，让 `vexra-adb` 在不依赖 Raft artifact 的
 * 情况下保持可插拔边界。</p>
 */
public interface AdbSqlDistributedRuntimeExtension {
  /**
   * 判断当前扩展是否支持指定 scan client。
   *
   * @param scanClient table-engine 参数中的 scan client 名称
   * @return 支持时返回 true
   */
  boolean supportsScanClient(String scanClient);

  /**
   * 创建远端 region scan 目标。
   *
   * @param store 本地 ADB store
   * @param config SQL 分布式配置
   * @return scan 目标
   * @throws SQLException 创建失败时抛出
   */
  AdbSqlDistributedScanTarget createScanTarget(DbStore store,
      AdbSqlDistributedScanConfig config) throws SQLException;

  /**
   * 判断当前扩展是否支持指定 write client。
   *
   * @param writeClient table-engine 参数中的 write client 名称
   * @return 支持时返回 true
   */
  boolean supportsWriteClient(String writeClient);

  /**
   * 创建远端 region write 目标。
   *
   * @param config SQL 分布式配置
   * @return write 目标
   * @throws SQLException 创建失败时抛出
   */
  AdbSqlDistributedWriteTarget createWriteTarget(
      AdbSqlDistributedScanConfig config) throws SQLException;
}
