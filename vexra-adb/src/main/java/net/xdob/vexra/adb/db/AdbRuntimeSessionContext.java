package net.xdob.vexra.adb.db;

import java.util.Objects;

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
  private volatile AdbControlPlaneSnapshot snapshot;

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
    this.txnManager = Objects.requireNonNull(txnManager, "txnManager == null");
    this.controlPlaneClient = Objects.requireNonNull(controlPlaneClient,
        "controlPlaneClient == null");
    this.commitClient = Objects.requireNonNull(commitClient,
        "commitClient == null");
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
        next.getRouter(), commitClient));
    this.snapshot = next;
    return next;
  }

  /**
   * 关闭分布式 runtime context，恢复 TxnManager 单机默认路径。
   */
  public void detach() {
    txnManager.setRegionReadRouter(null);
    txnManager.setRegionCommitCoordinator(null);
    txnManager.setTimestampProvider(null);
    snapshot = null;
  }
}
