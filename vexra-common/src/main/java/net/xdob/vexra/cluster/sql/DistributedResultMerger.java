package net.xdob.vexra.cluster.sql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 分布式查询结果合并器。
 *
 * <p>该合并器提供最小结果合并能力：跨 region 行集合合并，以及 count 下推结果求和。
 * 后续 agg/join/sort 可以在此基础上扩展。</p>
 */
public final class DistributedResultMerger {
  /**
   * 合并多个 region 的普通行结果。
   *
   * @param results region 查询结果
   * @return 合并后的行集合
   */
  public List<Map<String, Object>> mergeRows(List<RegionQueryResult> results) {
    Objects.requireNonNull(results, "results == null");
    List<Map<String, Object>> rows = new ArrayList<>();
    for (RegionQueryResult result : results) {
      rows.addAll(result.getRows());
    }
    return Collections.unmodifiableList(rows);
  }

  /**
   * 合并多个 region 的 count 下推结果。
   *
   * @param results region 查询结果
   * @return count 总和
   */
  public long mergeCount(List<RegionQueryResult> results) {
    Objects.requireNonNull(results, "results == null");
    long count = 0;
    for (RegionQueryResult result : results) {
      count += result.getCount();
    }
    return count;
  }
}
