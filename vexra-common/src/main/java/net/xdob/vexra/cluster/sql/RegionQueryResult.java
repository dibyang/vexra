package net.xdob.vexra.cluster.sql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Region 查询结果。
 *
 * <p>结果可以表示普通行集合，也可以表示 count 下推结果。行使用有序 Map 保存列名和值，
 * 便于后续 SQL 层按投影顺序合并。</p>
 */
public final class RegionQueryResult {
  private final String regionId;
  private final List<Map<String, Object>> rows;
  private final long count;

  /**
   * 创建 region 查询结果。
   *
   * @param regionId region 标识
   * @param rows 查询行集合
   * @param count count 下推结果
   */
  public RegionQueryResult(String regionId, List<Map<String, Object>> rows,
      long count) {
    if (regionId == null || regionId.trim().isEmpty()) {
      throw new IllegalArgumentException("regionId is empty");
    }
    if (count < 0) {
      throw new IllegalArgumentException("count is negative");
    }
    this.regionId = regionId.trim();
    this.rows = immutableRows(rows);
    this.count = count;
  }

  public String getRegionId() {
    return regionId;
  }

  public List<Map<String, Object>> getRows() {
    return rows;
  }

  public long getCount() {
    return count;
  }

  private static List<Map<String, Object>> immutableRows(
      List<Map<String, Object>> rows) {
    if (rows == null || rows.isEmpty()) {
      return Collections.emptyList();
    }
    List<Map<String, Object>> copy = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      copy.add(Collections.unmodifiableMap(new LinkedHashMap<>(row)));
    }
    return Collections.unmodifiableList(copy);
  }
}
