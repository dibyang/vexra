package net.xdob.vexra.adb.ha2;

import net.xdob.vexra.adb.db.AdbControlPlaneClient;
import net.xdob.vexra.adb.db.AdbControlPlaneSnapshot;
import net.xdob.vexra.adb.db.AdbPrimaryLockStatus;
import net.xdob.vexra.adb.db.AdbPrimaryLockStatusReader;
import net.xdob.vexra.adb.db.AdbTxnLock;
import net.xdob.vexra.cluster.region.RegionMetadata;

import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 基于控制面 route snapshot 的 primary lock 状态读取器。
 *
 * <p>该实现先按 lock 的 primary logical key 在当前 route snapshot 中定位 region，
 * 再读取 region metadata 中的 leaderId，并从 {@link AdbRClientRegistry} 找到该
 * leader 对应的 {@link RClient}。真正的远端 read request 仍复用
 * {@link AdbRaftPrimaryLockStatusReader}，因此 resolver 主流程无需关心 region
 * 路由和 client 选择细节。</p>
 */
public final class AdbRoutedPrimaryLockStatusReader
    implements AdbPrimaryLockStatusReader {
  private final String dbName;
  private final Supplier<AdbControlPlaneSnapshot> snapshotSupplier;
  private final AdbRClientRegistry registry;

  /**
   * 创建 routed primary lock 状态读取器。
   *
   * @param dbName ADB 数据库名
   * @param controlPlaneClient 控制面客户端
   * @param registry RClient 注册表
   */
  public AdbRoutedPrimaryLockStatusReader(String dbName,
      AdbControlPlaneClient controlPlaneClient, AdbRClientRegistry registry) {
    this(dbName, () -> Objects.requireNonNull(controlPlaneClient,
        "controlPlaneClient == null").getSnapshot(), registry);
  }

  /**
   * 创建 routed primary lock 状态读取器。
   *
   * @param dbName ADB 数据库名
   * @param snapshotSupplier 当前 route snapshot 提供器
   * @param registry RClient 注册表
   */
  public AdbRoutedPrimaryLockStatusReader(String dbName,
      Supplier<AdbControlPlaneSnapshot> snapshotSupplier,
      AdbRClientRegistry registry) {
    this.dbName = normalize(dbName, "dbName");
    this.snapshotSupplier = Objects.requireNonNull(snapshotSupplier,
        "snapshotSupplier == null");
    this.registry = Objects.requireNonNull(registry, "registry == null");
  }

  @Override
  public AdbPrimaryLockStatus readPrimaryStatus(AdbTxnLock lock)
      throws SQLException {
    Objects.requireNonNull(lock, "lock == null");
    RegionMetadata region = routePrimaryRegion(lock);
    String leaderId = region.getReplicaMetadata().getLeaderId();
    if (leaderId == null || leaderId.trim().isEmpty()) {
      throw new SQLException("Primary region has no leader, regionId="
          + region.getRegionId() + ", txnId=" + lock.getTxnId());
    }
    RClient client = registry.get(leaderId).orElseThrow(() ->
        new SQLException("No RClient registered for leaderId=" + leaderId
            + ", regionId=" + region.getRegionId() + ", txnId="
            + lock.getTxnId()));
    return new AdbRaftPrimaryLockStatusReader(dbName, client)
        .readPrimaryStatus(lock);
  }

  private RegionMetadata routePrimaryRegion(AdbTxnLock lock)
      throws SQLException {
    AdbControlPlaneSnapshot snapshot = snapshotSupplier.get();
    if (snapshot == null) {
      throw new SQLException("ADB control-plane snapshot is null");
    }
    try {
      return snapshot.getRouter().route(lock.getPrimaryKey());
    } catch (RuntimeException e) {
      throw new SQLException("Failed to route primary lock, txnId="
          + lock.getTxnId(), e);
    }
  }

  private static String normalize(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return value.trim();
  }
}
