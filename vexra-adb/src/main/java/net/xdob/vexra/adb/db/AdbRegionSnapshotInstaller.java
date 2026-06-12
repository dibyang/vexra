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
  private final String localReplicaId;

  /**
   * 创建 ADB region snapshot 安装器。
   *
   * @param store ADB store
   * @param localReplicaId 当前节点副本标识
   */
  public AdbRegionSnapshotInstaller(DbStore store, String localReplicaId) {
    this.store = Objects.requireNonNull(store, "store == null");
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
  }

  private static String normalize(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return value.trim();
  }
}
