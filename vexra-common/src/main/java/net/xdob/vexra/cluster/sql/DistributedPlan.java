package net.xdob.vexra.cluster.sql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 分布式执行计划。
 *
 * <p>该计划承载多个 region scan task 和结果合并策略，是 h2db 本地计划与 Vexra
 * region 执行器之间的中间层。</p>
 */
public final class DistributedPlan {
  private final List<RegionScanTask> tasks;
  private final boolean countOnly;

  /**
   * 创建分布式执行计划。
   *
   * @param tasks region scan task 列表
   * @param countOnly 是否为 count 聚合计划
   */
  public DistributedPlan(List<RegionScanTask> tasks, boolean countOnly) {
    Objects.requireNonNull(tasks, "tasks == null");
    if (tasks.isEmpty()) {
      throw new IllegalArgumentException("tasks is empty");
    }
    this.tasks = Collections.unmodifiableList(new ArrayList<>(tasks));
    this.countOnly = countOnly;
  }

  public List<RegionScanTask> getTasks() {
    return tasks;
  }

  public boolean isCountOnly() {
    return countOnly;
  }
}
