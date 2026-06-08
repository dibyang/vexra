package net.xdob.vexra.cluster.region;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Region range router。
 *
 * <p>Router 持有一份不可变 region 元数据快照，按 key range 将点查或范围扫描路由到
 * 目标 region。构造时会拒绝重叠 range，避免一个 key 被路由到多个 region。</p>
 */
public final class RegionRouter {
  private final List<RegionMetadata> regions;

  /**
   * 创建 region router。
   *
   * @param regions region 元数据集合
   */
  public RegionRouter(Collection<RegionMetadata> regions) {
    Objects.requireNonNull(regions, "regions == null");
    List<RegionMetadata> copy = new ArrayList<>(regions);
    if (copy.isEmpty()) {
      throw new IllegalArgumentException("regions is empty");
    }
    Collections.sort(copy, Comparator.comparing(
        region -> new ByteArrayComparable(region.getRange().getStartKey())));
    validateNoOverlap(copy);
    this.regions = Collections.unmodifiableList(copy);
  }

  public List<RegionMetadata> getRegions() {
    return regions;
  }

  /**
   * 将单个 key 路由到 region。
   *
   * @param key 待路由 key
   * @return 命中的 region
   * @throws IllegalArgumentException 当没有 region 覆盖该 key 时抛出
   */
  public RegionMetadata route(byte[] key) {
    for (RegionMetadata region : regions) {
      if (region.contains(key)) {
        return region;
      }
    }
    throw new IllegalArgumentException("no region contains key");
  }

  /**
   * 将 key range 路由到所有相交 region。
   *
   * @param range 待路由 range
   * @return 与 range 相交的 region 列表
   */
  public List<RegionMetadata> route(KeyRange range) {
    Objects.requireNonNull(range, "range == null");
    List<RegionMetadata> result = new ArrayList<>();
    for (RegionMetadata region : regions) {
      if (region.getRange().overlaps(range)) {
        result.add(region);
      }
    }
    return Collections.unmodifiableList(result);
  }

  private static void validateNoOverlap(List<RegionMetadata> regions) {
    for (int i = 1; i < regions.size(); i++) {
      RegionMetadata previous = regions.get(i - 1);
      RegionMetadata current = regions.get(i);
      if (previous.getRange().overlaps(current.getRange())) {
        throw new IllegalArgumentException(
            "overlapped regions: " + previous.getRegionId()
                + " and " + current.getRegionId());
      }
    }
  }

  private static final class ByteArrayComparable
      implements Comparable<ByteArrayComparable> {
    private final byte[] value;

    private ByteArrayComparable(byte[] value) {
      this.value = value;
    }

    @Override
    public int compareTo(ByteArrayComparable other) {
      return KeyRange.compare(value, other.value);
    }
  }
}
