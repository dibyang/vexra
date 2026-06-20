package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.DataKey;
import net.xdob.vexra.cluster.region.RegionMetadata;
import net.xdob.vexra.cluster.region.RegionRouter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * ADB 事务 region 分类器。
 *
 * <p>该类是 GA-04 事务最小生产化的边界组件：它只根据当前 route snapshot 和
 * write set 判断事务命中的 region 集合，不执行提交、不修改事务状态。生产 guard
 * 可以基于该结果放行单 region 事务，并默认拒绝跨 region 事务。</p>
 */
public final class AdbTxnRegionClassifier {
  private final RegionRouter router;
  private final Function<DataKey, DataKey> keyMapper;

  /**
   * 创建事务 region 分类器。
   *
   * @param router region 路由快照
   * @param keyMapper 写入 key 映射器
   */
  public AdbTxnRegionClassifier(RegionRouter router,
      Function<DataKey, DataKey> keyMapper) {
    this.router = Objects.requireNonNull(router, "router == null");
    this.keyMapper = Objects.requireNonNull(keyMapper, "keyMapper == null");
  }

  /**
   * 对 write set 命中的 region 去重并保持首次命中顺序。
   *
   * @param writeKeys 事务 write set
   * @return region id 列表
   */
  public List<String> classify(Collection<DataKey> writeKeys) {
    Objects.requireNonNull(writeKeys, "writeKeys == null");
    if (writeKeys.isEmpty()) {
      throw new IllegalArgumentException("writeKeys is empty");
    }
    Set<String> regionIds = new LinkedHashSet<>();
    for (DataKey key : writeKeys) {
      DataKey mappedKey = Objects.requireNonNull(keyMapper.apply(key),
          "mapped write key is null");
      RegionMetadata region = router.route(mappedKey.toBytes());
      regionIds.add(region.getRegionId());
    }
    return new ArrayList<>(regionIds);
  }
}
