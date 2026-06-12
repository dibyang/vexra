package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.RowPrefix;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.cluster.region.KeyRange;
import net.xdob.vexra.cluster.region.RegionMetadata;
import net.xdob.vexra.cluster.region.RegionRouter;
import net.xdob.vexra.cluster.sql.DistributedPlan;
import net.xdob.vexra.cluster.sql.RegionScanTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * ADB 分布式执行计划 adapter。
 *
 * <p>该类把 ADB/h2db 本地 table row scan 意图转换为按 region 切分的
 * {@link DistributedPlan}。它只负责 key range 到 region task 的转换和诊断解释，不直接
 * 修改 h2db optimizer，也不做代价选择。</p>
 */
public final class AdbDistributedPlanAdapter {
  private final RegionRouter router;

  /**
   * 创建 ADB 分布式计划 adapter。
   *
   * @param router 当前 route snapshot 中的 region router
   */
  public AdbDistributedPlanAdapter(RegionRouter router) {
    this.router = Objects.requireNonNull(router, "router == null");
  }

  /**
   * 为 table row scan 构建分布式执行计划。
   *
   * @param tabId ADB 表标识
   * @param minRowId 起始 rowId，null 表示表起始
   * @param maxRowId 结束 rowId，null 表示表结束
   * @param projections 下推投影描述
   * @param filters 下推过滤描述
   * @param limit 下推 limit，0 表示无限制
   * @param readTimestamp 读时间戳
   * @param countOnly 是否为 count-only 计划
   * @return 分布式执行计划
   */
  public DistributedPlan tableRowScan(TabId tabId, Long minRowId,
      Long maxRowId, List<String> projections, List<String> filters, int limit,
      long readTimestamp, boolean countOnly) {
    Objects.requireNonNull(tabId, "tabId == null");
    if (minRowId != null && maxRowId != null && minRowId > maxRowId) {
      throw new IllegalArgumentException("minRowId is greater than maxRowId");
    }
    if (limit < 0) {
      throw new IllegalArgumentException("limit is negative: " + limit);
    }
    if (readTimestamp < 0) {
      throw new IllegalArgumentException("readTimestamp is negative: "
          + readTimestamp);
    }

    KeyRange scanRange = new KeyRange(tableScanStartKey(tabId, minRowId),
        tableScanEndKey(tabId, maxRowId));
    List<RegionScanTask> tasks = new ArrayList<>();
    for (RegionMetadata region : router.route(scanRange)) {
      KeyRange taskRange = intersect(scanRange, region.getRange());
      if (taskRange != null) {
        tasks.add(new RegionScanTask(region.getRegionId(), taskRange,
            projections, filters, limit, readTimestamp));
      }
    }
    return new DistributedPlan(tasks, countOnly);
  }

  /**
   * 输出 `EXPLAIN DISTRIBUTED` 风格的诊断文本。
   *
   * @param plan 分布式执行计划
   * @return 每个 region task 一行的解释文本
   */
  public List<String> explain(DistributedPlan plan) {
    Objects.requireNonNull(plan, "plan == null");
    List<String> lines = new ArrayList<>();
    for (RegionScanTask task : plan.getTasks()) {
      lines.add("REGION_SCAN region=" + task.getRegionId()
          + " range=[" + toHex(task.getKeyRange().getStartKey()) + ","
          + toHex(task.getKeyRange().getEndKey()) + ")"
          + " countOnly=" + plan.isCountOnly()
          + " limit=" + task.getLimit()
          + " readTs=" + task.getReadTimestamp());
    }
    return Collections.unmodifiableList(lines);
  }

  private static KeyRange intersect(KeyRange left, KeyRange right) {
    byte[] start = maxStart(left.getStartKey(), right.getStartKey());
    byte[] end = minEnd(left.getEndKey(), right.getEndKey());
    if (start.length > 0 && end.length > 0 && KeyRange.compare(start, end) >= 0) {
      return null;
    }
    return new KeyRange(start, end);
  }

  private static byte[] maxStart(byte[] left, byte[] right) {
    if (left.length == 0) {
      return right;
    }
    if (right.length == 0) {
      return left;
    }
    return KeyRange.compare(left, right) >= 0 ? left : right;
  }

  private static byte[] minEnd(byte[] left, byte[] right) {
    if (left.length == 0) {
      return right;
    }
    if (right.length == 0) {
      return left;
    }
    return KeyRange.compare(left, right) <= 0 ? left : right;
  }

  private static byte[] tableScanStartKey(TabId tabId, Long minRowId) {
    return minRowId != null ? RowKey.of(tabId, minRowId).toBytes()
        : RowPrefix.of(tabId).toBytes();
  }

  private static byte[] tableScanEndKey(TabId tabId, Long maxRowId) {
    if (maxRowId != null) {
      return normalizeEnd(KeyCodec.prefixEnd(RowKey.of(tabId, maxRowId)
          .toBytes()));
    }
    return normalizeEnd(KeyCodec.prefixEnd(RowPrefix.of(tabId).toBytes()));
  }

  private static byte[] normalizeEnd(byte[] endKey) {
    return endKey == null ? new byte[0] : endKey;
  }

  private static String toHex(byte[] bytes) {
    StringBuilder builder = new StringBuilder();
    for (byte b : bytes) {
      builder.append(String.format("%02x", b & 0xff));
    }
    return builder.toString();
  }
}
