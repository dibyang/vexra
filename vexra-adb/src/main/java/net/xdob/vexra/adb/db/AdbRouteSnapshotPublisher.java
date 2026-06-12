package net.xdob.vexra.adb.db;

import net.xdob.vexra.cluster.region.RegionMetadata;

import java.util.Collection;

/**
 * ADB route snapshot 发布接口。
 *
 * <p>该接口位于控制面写入边界，用于发布 split/merge 后的新 region 元数据集合并推进
 * route epoch。生产实现应由持久化控制面或 PD-like 服务复制该变更；测试和单机原型可由
 * 内存控制面实现。</p>
 */
public interface AdbRouteSnapshotPublisher extends AdbControlPlaneClient {
  /**
   * 发布新的 region 元数据快照。
   *
   * @param newRegions 新 region 元数据集合
   * @return 发布后的 route epoch
   */
  long publishRegions(Collection<RegionMetadata> newRegions);
}
