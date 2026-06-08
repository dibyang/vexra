package net.xdob.vexra.cluster.region;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Region snapshot 安装计划。
 *
 * <p>该对象描述某个 region snapshot 应安装到哪些副本，以及 snapshot 对应的
 * term/index。真正的文件传输仍由现有 Raft snapshot install 流程完成。</p>
 */
public final class RegionSnapshotInstallPlan {
  private final String regionId;
  private final long snapshotTerm;
  private final long snapshotIndex;
  private final List<String> targetReplicaIds;

  /**
   * 创建 region snapshot 安装计划。
   *
   * @param regionId region 标识
   * @param snapshotTerm snapshot term
   * @param snapshotIndex snapshot index
   * @param targetReplicaIds 目标副本标识集合
   */
  public RegionSnapshotInstallPlan(String regionId, long snapshotTerm,
      long snapshotIndex, List<String> targetReplicaIds) {
    this.regionId = normalize(regionId, "regionId");
    if (snapshotTerm < 0 || snapshotIndex < 0) {
      throw new IllegalArgumentException("snapshot term/index must be non-negative");
    }
    this.snapshotTerm = snapshotTerm;
    this.snapshotIndex = snapshotIndex;
    this.targetReplicaIds = immutableTargets(targetReplicaIds);
  }

  public String getRegionId() {
    return regionId;
  }

  public long getSnapshotTerm() {
    return snapshotTerm;
  }

  public long getSnapshotIndex() {
    return snapshotIndex;
  }

  public List<String> getTargetReplicaIds() {
    return targetReplicaIds;
  }

  private static List<String> immutableTargets(List<String> targetReplicaIds) {
    Objects.requireNonNull(targetReplicaIds, "targetReplicaIds == null");
    if (targetReplicaIds.isEmpty()) {
      throw new IllegalArgumentException("targetReplicaIds is empty");
    }
    List<String> copy = new ArrayList<>();
    for (String targetReplicaId : targetReplicaIds) {
      copy.add(normalize(targetReplicaId, "targetReplicaId"));
    }
    return Collections.unmodifiableList(copy);
  }

  private static String normalize(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return value.trim();
  }
}
