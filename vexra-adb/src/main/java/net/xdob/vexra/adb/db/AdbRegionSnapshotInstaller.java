package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.cluster.region.RegionSnapshotInstallPlan;

import java.io.IOException;
import java.util.Objects;

/**
 * ADB region snapshot 安装器。
 *
 * <p>该类是 region snapshot install 到 ADB store 的运行时桥接层。它只负责校验当前
 * 节点是否是计划中的目标副本，并调用 {@link DbStore#restore(String)} 安装已经传输完成的
 * snapshot 目录；真实 Raft snapshot chunk 接收、校验和重试由后续网络/复制层负责。</p>
 */
public final class AdbRegionSnapshotInstaller {
  private final DbStore store;
  private final TxnManager txnManager;
  private final String localReplicaId;

  /**
   * 创建 ADB region snapshot 安装器。
   *
   * @param store ADB store
   * @param localReplicaId 当前节点副本标识
   */
  public AdbRegionSnapshotInstaller(DbStore store, String localReplicaId) {
    this(store, null, localReplicaId);
  }

  /**
   * 创建带事务缓存失效能力的 ADB region snapshot 安装器。
   *
   * <p>snapshot 安装会整体替换底层 store 的可见内容。如果同一进程内已有
   * {@link TxnManager} 使用 trusted committed row cache，restore 成功后必须让这些
   * 从 store 派生的缓存失效，避免后续点查继续返回安装 snapshot 前的旧行。</p>
   *
   * @param store ADB store
   * @param txnManager 需要随 snapshot 安装失效缓存的事务管理器；允许为 null
   * @param localReplicaId 当前节点副本标识
   */
  public AdbRegionSnapshotInstaller(DbStore store, TxnManager txnManager,
      String localReplicaId) {
    this.store = Objects.requireNonNull(store, "store == null");
    this.txnManager = txnManager;
    this.localReplicaId = normalize(localReplicaId, "localReplicaId");
  }

  /**
   * 安装指定 region snapshot 目录。
   *
   * @param plan region snapshot install 计划
   * @param snapshotDir 已传输完成的 snapshot 目录
   * @throws IOException 当当前副本不是目标、副本恢复失败或目录无效时抛出
   */
  public void install(RegionSnapshotInstallPlan plan, String snapshotDir)
      throws IOException {
    Objects.requireNonNull(plan, "plan == null");
    if (!plan.getTargetReplicaIds().contains(localReplicaId)) {
      throw new IOException("Local replica is not a snapshot target: "
          + localReplicaId + ", regionId=" + plan.getRegionId());
    }
    store.restore(Objects.requireNonNull(snapshotDir, "snapshotDir == null"));
    if (txnManager != null) {
      txnManager.invalidateStoreDerivedCaches();
    }
  }

  private static String normalize(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return value.trim();
  }
}
