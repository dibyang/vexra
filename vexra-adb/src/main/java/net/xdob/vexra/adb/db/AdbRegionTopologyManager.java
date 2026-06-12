package net.xdob.vexra.adb.db;

import net.xdob.vexra.cluster.region.KeyRange;
import net.xdob.vexra.cluster.region.RegionMetadata;
import net.xdob.vexra.ha.VirtualNodeMetadata;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * ADB region 拓扑管理器。
 *
 * <p>该管理器负责把 split/merge 的元数据变更发布到控制面 route snapshot。它不搬迁
 * 数据文件，也不执行 Raft 成员变更；真实数据迁移和副本修复由后续 region Raft/snapshot
 * 流程接管。本阶段的关键约束是：先让路由 epoch 可推进，读写路径能看到新的 region
 * 切分结果。</p>
 */
public final class AdbRegionTopologyManager {
  private final AdbRouteSnapshotPublisher publisher;

  /**
   * 创建 region 拓扑管理器。
   *
   * @param publisher route snapshot 发布接口
   */
  public AdbRegionTopologyManager(AdbRouteSnapshotPublisher publisher) {
    this.publisher = Objects.requireNonNull(publisher, "publisher == null");
  }

  /**
   * 将父 region 按 split key 拆分为左右两个子 region。
   *
   * @param parentRegionId 父 region 标识
   * @param splitKey split key，必须位于父 region 内部
   * @param leftRegionId 左子 region 标识
   * @param rightRegionId 右子 region 标识
   * @return 发布后的 route snapshot
   */
  public AdbControlPlaneSnapshot splitRegion(String parentRegionId,
      byte[] splitKey, String leftRegionId, String rightRegionId) {
    Objects.requireNonNull(splitKey, "splitKey == null");
    if (splitKey.length == 0) {
      throw new IllegalArgumentException("splitKey is empty");
    }
    AdbControlPlaneSnapshot snapshot = publisher.getSnapshot();
    RegionMetadata parent = findRegion(snapshot, parentRegionId);
    validateSplitKey(parent, splitKey);

    List<RegionMetadata> nextRegions = new ArrayList<>();
    long nextEpoch = parent.getEpoch() + 1;
    for (RegionMetadata region : snapshot.getRegions()) {
      if (!region.getRegionId().equals(parent.getRegionId())) {
        nextRegions.add(region);
      }
    }
    nextRegions.add(new RegionMetadata(leftRegionId,
        new KeyRange(parent.getRange().getStartKey(), splitKey), nextEpoch,
        childReplicaMetadata(parent, leftRegionId, nextEpoch)));
    nextRegions.add(new RegionMetadata(rightRegionId,
        new KeyRange(splitKey, parent.getRange().getEndKey()), nextEpoch,
        childReplicaMetadata(parent, rightRegionId, nextEpoch)));
    publisher.publishRegions(nextRegions);
    return publisher.getSnapshot();
  }

  /**
   * 合并两个相邻 region 的元数据。
   *
   * @param leftRegionId 左 region 标识
   * @param rightRegionId 右 region 标识
   * @param mergedRegionId 合并后 region 标识
   * @return 发布后的 route snapshot
   */
  public AdbControlPlaneSnapshot mergeAdjacentRegions(String leftRegionId,
      String rightRegionId, String mergedRegionId) {
    AdbControlPlaneSnapshot snapshot = publisher.getSnapshot();
    RegionMetadata left = findRegion(snapshot, leftRegionId);
    RegionMetadata right = findRegion(snapshot, rightRegionId);
    if (!Arrays.equals(left.getRange().getEndKey(),
        right.getRange().getStartKey())) {
      throw new IllegalArgumentException("regions are not adjacent: "
          + leftRegionId + ", " + rightRegionId);
    }

    List<RegionMetadata> nextRegions = new ArrayList<>();
    long nextEpoch = Math.max(left.getEpoch(), right.getEpoch()) + 1;
    for (RegionMetadata region : snapshot.getRegions()) {
      if (!region.getRegionId().equals(leftRegionId)
          && !region.getRegionId().equals(rightRegionId)) {
        nextRegions.add(region);
      }
    }
    nextRegions.add(new RegionMetadata(mergedRegionId,
        new KeyRange(left.getRange().getStartKey(), right.getRange().getEndKey()),
        nextEpoch, childReplicaMetadata(left, mergedRegionId, nextEpoch)));
    publisher.publishRegions(nextRegions);
    return publisher.getSnapshot();
  }

  private RegionMetadata findRegion(AdbControlPlaneSnapshot snapshot,
      String regionId) {
    String normalized = normalize(regionId, "regionId");
    for (RegionMetadata region : snapshot.getRegions()) {
      if (region.getRegionId().equals(normalized)) {
        return region;
      }
    }
    throw new IllegalArgumentException("region not found: " + normalized);
  }

  private void validateSplitKey(RegionMetadata parent, byte[] splitKey) {
    KeyRange range = parent.getRange();
    if (!range.contains(splitKey)) {
      throw new IllegalArgumentException("splitKey is outside parent region");
    }
    if (Arrays.equals(splitKey, range.getStartKey())
        || Arrays.equals(splitKey, range.getEndKey())) {
      throw new IllegalArgumentException("splitKey must be inside parent range");
    }
  }

  private VirtualNodeMetadata childReplicaMetadata(RegionMetadata source,
      String regionId, long epoch) {
    VirtualNodeMetadata replica = source.getReplicaMetadata();
    return new VirtualNodeMetadata("vn-" + normalize(regionId, "regionId"),
        epoch, replica.getLeaderId(), replica.getReplicas(),
        replica.getCommitIndex(), replica.getTerm(),
        replica.getLeaseUntilMillis());
  }

  private static String normalize(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return value.trim();
  }
}
