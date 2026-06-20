package net.xdob.vexra.adb.db;

import java.sql.SQLException;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * ADB 分布式运行时 session context。
 *
 * <p>该对象把控制面 route snapshot 和 TSO 安装到单个 {@link TxnManager}。它不直接
 * 依赖 H2 SessionLocal，因此可以在 JDBC 集成、测试或后续 server session 层复用。</p>
 */
public final class AdbRuntimeSessionContext {
  private final TxnManager txnManager;
  private final AdbControlPlaneClient controlPlaneClient;
  private final AdbRegionCommitClient commitClient;
  private final long routeTtlMillis;
  private final LongSupplier clock;
  private volatile AdbControlPlaneSnapshot snapshot;
  private volatile long lastControlPlaneRefreshMillis;

  /**
   * 创建 ADB runtime session context。
   *
   * @param txnManager 事务管理器
   * @param controlPlaneClient 控制面客户端
   * @param commitClient region commit client
   */
  public AdbRuntimeSessionContext(TxnManager txnManager,
      AdbControlPlaneClient controlPlaneClient,
      AdbRegionCommitClient commitClient) {
    this(txnManager, controlPlaneClient, commitClient, Long.MAX_VALUE,
        System::currentTimeMillis);
  }

  /**
   * 创建带 route TTL 的 ADB runtime session context。
   *
   * @param txnManager 事务管理器
   * @param controlPlaneClient 控制面客户端
   * @param commitClient region commit client
   * @param routeTtlMillis 控制面路由 TTL，超过后写入会失败
   * @param clock 当前时间来源
   */
  public AdbRuntimeSessionContext(TxnManager txnManager,
      AdbControlPlaneClient controlPlaneClient,
      AdbRegionCommitClient commitClient, long routeTtlMillis,
      LongSupplier clock) {
    this.txnManager = Objects.requireNonNull(txnManager, "txnManager == null");
    this.controlPlaneClient = Objects.requireNonNull(controlPlaneClient,
        "controlPlaneClient == null");
    this.commitClient = Objects.requireNonNull(commitClient,
        "commitClient == null");
    if (routeTtlMillis <= 0) {
      throw new IllegalArgumentException("routeTtlMillis must be positive");
    }
    this.routeTtlMillis = routeTtlMillis;
    this.clock = Objects.requireNonNull(clock, "clock == null");
    this.txnManager.setTimestampProvider(
        new AdbControlPlaneTimestampProvider(controlPlaneClient));
    refreshRouteSnapshot();
  }

  public AdbControlPlaneSnapshot getSnapshot() {
    return snapshot;
  }

  /**
   * 从控制面刷新 route snapshot，并安装到 TxnManager 读写路径。
   *
   * @return 刷新后的快照
   */
  public AdbControlPlaneSnapshot refreshRouteSnapshot() {
    AdbControlPlaneSnapshot next = controlPlaneClient.getSnapshot();
    txnManager.setRegionReadRouter(new RegionAwareAdbReadRouter(
        next.getRouter()));
    txnManager.setRegionCommitCoordinator(new AdbRegionCommitCoordinator(
        next.getRouter(), commitClient, java.util.function.Function.identity(),
        false, AdbDurableCommitRecorder.noop(),
        this::requireControlPlaneFreshForWrite));
    this.snapshot = next;
    this.lastControlPlaneRefreshMillis = clock.getAsLong();
    return next;
  }

  /**
   * 如果控制面 route epoch 已经变化，则刷新本地路由快照。
   *
   * @return 发生刷新时返回 true，否则返回 false
   */
  public boolean refreshRouteSnapshotIfChanged() {
    AdbControlPlaneSnapshot current = snapshot;
    if (current == null) {
      refreshRouteSnapshot();
      return true;
    }
    AdbRouteWatch watch = controlPlaneClient.watchRoutes(
        current.getRouteEpoch());
    if (watch.isRouteChanged()) {
      refreshRouteSnapshot();
      return true;
    }
    return false;
  }

  /**
   * 校验当前控制面路由快照是否仍允许写入。
   *
   * @throws SQLException 当快照超过 TTL 时抛出
   */
  public void requireControlPlaneFreshForWrite() throws SQLException {
    long age = clock.getAsLong() - lastControlPlaneRefreshMillis;
    if (age > routeTtlMillis) {
      throw new SQLException("ADB control-plane route snapshot expired, "
          + "ageMillis=" + age + ", ttlMillis=" + routeTtlMillis);
    }
  }

  /**
   * 关闭分布式 runtime context，恢复 TxnManager 单机默认路径。
   */
  public void detach() {
    txnManager.setRegionReadRouter(null);
    txnManager.setRegionCommitCoordinator(null);
    txnManager.setTimestampProvider(null);
    snapshot = null;
    lastControlPlaneRefreshMillis = 0;
  }
}
