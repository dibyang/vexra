package net.xdob.vexra.cluster.sql;

import net.xdob.vexra.cluster.region.KeyRange;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Region scan task。
 *
 * <p>该任务描述一次可下推到 region 的扫描，包括 key range、projection、filter、
 * limit 和 read timestamp。真实执行器可以把它转换为 ADB/LDB scan。</p>
 */
public final class RegionScanTask {
  private final String regionId;
  private final KeyRange keyRange;
  private final List<String> projections;
  private final List<String> filters;
  private final int limit;
  private final long readTimestamp;

  /**
   * 创建 region scan task。
   *
   * @param regionId region 标识
   * @param keyRange 扫描 key range
   * @param projections 下推列
   * @param filters 下推过滤条件描述
   * @param limit 下推 limit，0 表示无限制
   * @param readTimestamp 读时间戳
   */
  public RegionScanTask(String regionId, KeyRange keyRange,
      List<String> projections, List<String> filters, int limit,
      long readTimestamp) {
    if (regionId == null || regionId.trim().isEmpty()) {
      throw new IllegalArgumentException("regionId is empty");
    }
    if (limit < 0) {
      throw new IllegalArgumentException("limit is negative");
    }
    if (readTimestamp < 0) {
      throw new IllegalArgumentException("readTimestamp is negative");
    }
    this.regionId = regionId.trim();
    this.keyRange = Objects.requireNonNull(keyRange, "keyRange == null");
    this.projections = immutableStrings(projections);
    this.filters = immutableStrings(filters);
    this.limit = limit;
    this.readTimestamp = readTimestamp;
  }

  public String getRegionId() {
    return regionId;
  }

  public KeyRange getKeyRange() {
    return keyRange;
  }

  public List<String> getProjections() {
    return projections;
  }

  public List<String> getFilters() {
    return filters;
  }

  public int getLimit() {
    return limit;
  }

  public long getReadTimestamp() {
    return readTimestamp;
  }

  private static List<String> immutableStrings(List<String> values) {
    if (values == null || values.isEmpty()) {
      return Collections.emptyList();
    }
    List<String> copy = new ArrayList<>();
    for (String value : values) {
      if (value != null && !value.trim().isEmpty()) {
        copy.add(value.trim());
      }
    }
    return Collections.unmodifiableList(copy);
  }
}
