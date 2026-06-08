package net.xdob.vexra.cluster.sql;

import net.xdob.vexra.cluster.region.KeyRange;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分布式 SQL 执行计划回归测试。
 *
 * <p>测试覆盖 ADB-Cluster-05 的最小公共模型：region scan task、filter/count 下推描述
 * 以及跨 region 结果合并。</p>
 */
class DistributedSqlPlanTest {
  /**
   * 验证分布式计划能承载多个 region scan task 和下推属性。
   */
  @Test
  void shouldDescribeDistributedRegionScanPlan() {
    RegionScanTask task = new RegionScanTask("r1",
        new KeyRange(bytes("a"), bytes("m")),
        Arrays.asList("id", "name"),
        Arrays.asList("id >= 1"), 10, 100);
    DistributedPlan plan = new DistributedPlan(Arrays.asList(task), false);

    assertFalse(plan.isCountOnly());
    assertEquals(1, plan.getTasks().size());
    assertEquals("r1", plan.getTasks().get(0).getRegionId());
    assertEquals("name", plan.getTasks().get(0).getProjections().get(1));
    assertEquals("id >= 1", plan.getTasks().get(0).getFilters().get(0));
    assertEquals(10, plan.getTasks().get(0).getLimit());
    assertEquals(100, plan.getTasks().get(0).getReadTimestamp());
  }

  /**
   * 验证普通行结果可以跨 region 顺序合并。
   */
  @Test
  void shouldMergeRowsAcrossRegions() {
    DistributedResultMerger merger = new DistributedResultMerger();

    List<Map<String, Object>> rows = merger.mergeRows(Arrays.asList(
        new RegionQueryResult("r1", Arrays.asList(row(1, "a")), 0),
        new RegionQueryResult("r2", Arrays.asList(row(2, "b")), 0)));

    assertEquals(2, rows.size());
    assertEquals("a", rows.get(0).get("name"));
    assertEquals("b", rows.get(1).get("name"));
  }

  /**
   * 验证 count 下推结果可以跨 region 求和。
   */
  @Test
  void shouldMergeCountAcrossRegions() {
    DistributedResultMerger merger = new DistributedResultMerger();

    long count = merger.mergeCount(Arrays.asList(
        new RegionQueryResult("r1", null, 3),
        new RegionQueryResult("r2", null, 4)));

    assertEquals(7, count);
  }

  private static Map<String, Object> row(int id, String name) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", id);
    row.put("name", name);
    return row;
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
