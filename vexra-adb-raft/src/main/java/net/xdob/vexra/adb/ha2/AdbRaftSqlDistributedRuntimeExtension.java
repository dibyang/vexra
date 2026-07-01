package net.xdob.vexra.adb.ha2;

import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.adb.db.AdbDistributedRegionScanExecutor;
import net.xdob.vexra.adb.db.AdbRpcRegionCommitClient;
import net.xdob.vexra.adb.db.AdbSqlDistributedRuntimeExtension;
import net.xdob.vexra.adb.db.AdbSqlDistributedScanConfig;
import net.xdob.vexra.adb.db.AdbSqlDistributedScanTarget;
import net.xdob.vexra.adb.db.AdbSqlDistributedWriteTarget;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Properties;

/**
 * ADB SQL 分布式运行时的 Raft 扩展实现。
 *
 * <p>该类只存在于 `vexra-adb-raft`，负责把核心 ADB 的 region SPI 连接到
 * Vexra Raft client。核心 `vexra-adb` 通过 ServiceLoader 发现它，因此不会直接
 * 依赖 Raft artifact。</p>
 */
public final class AdbRaftSqlDistributedRuntimeExtension
    implements AdbSqlDistributedRuntimeExtension {
  private static final String RAFT = "raft";

  @Override
  public boolean supportsScanClient(String scanClient) {
    return RAFT.equals(normalize(scanClient));
  }

  @Override
  public AdbSqlDistributedScanTarget createScanTarget(DbStore store,
      AdbSqlDistributedScanConfig config) throws SQLException {
    RaftRClient rClient = new RaftRClient(properties(config));
    return new AdbSqlDistributedScanTarget(
        new AdbDistributedRegionScanExecutor(
            new AdbRaftRegionScanClient(config.getRaftDbName(), rClient)),
        rClient);
  }

  @Override
  public boolean supportsWriteClient(String writeClient) {
    return RAFT.equals(normalize(writeClient));
  }

  @Override
  public AdbSqlDistributedWriteTarget createWriteTarget(
      AdbSqlDistributedScanConfig config) throws SQLException {
    RaftRClient rClient = new RaftRClient(properties(config));
    return new AdbSqlDistributedWriteTarget(new AdbRpcRegionCommitClient(
        new AdbRaftRegionCommitTransport(config.getRaftDbName(), rClient),
        config.getWriteTimeoutMillis()), rClient);
  }

  private static Properties properties(AdbSqlDistributedScanConfig config) {
    Properties properties = new Properties();
    properties.setProperty("HA2.GROUP", config.getRaftGroup());
    properties.setProperty("HA2.NODES", config.getRaftPeers());
    return properties;
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }
}
