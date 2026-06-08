package net.xdob.vexra.cluster.ops;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 备份恢复计划。
 *
 * <p>计划描述备份恢复模式、目标 region、存储位置和 checkpoint 时间戳。
 * 真实备份工具可据此调度 region checkpoint、checksum 和恢复任务。</p>
 */
public final class BackupRestorePlan {
  private final String planId;
  private final BackupRestoreMode mode;
  private final List<String> regionIds;
  private final String location;
  private final long checkpointTs;

  /**
   * 创建备份恢复计划。
   *
   * @param planId 计划标识
   * @param mode 备份恢复模式
   * @param regionIds 目标 region 集合
   * @param location 存储位置
   * @param checkpointTs checkpoint 时间戳
   */
  public BackupRestorePlan(String planId, BackupRestoreMode mode,
      List<String> regionIds, String location, long checkpointTs) {
    this.planId = normalize(planId, "planId");
    this.mode = Objects.requireNonNull(mode, "mode == null");
    this.regionIds = immutableIds(regionIds);
    this.location = normalize(location, "location");
    if (checkpointTs < 0) {
      throw new IllegalArgumentException("checkpointTs is negative");
    }
    this.checkpointTs = checkpointTs;
  }

  public String getPlanId() {
    return planId;
  }

  public BackupRestoreMode getMode() {
    return mode;
  }

  public List<String> getRegionIds() {
    return regionIds;
  }

  public String getLocation() {
    return location;
  }

  public long getCheckpointTs() {
    return checkpointTs;
  }

  private static List<String> immutableIds(List<String> values) {
    Objects.requireNonNull(values, "regionIds == null");
    if (values.isEmpty()) {
      throw new IllegalArgumentException("regionIds is empty");
    }
    List<String> copy = new ArrayList<>();
    for (String value : values) {
      copy.add(normalize(value, "regionId"));
    }
    return Collections.unmodifiableList(copy);
  }

  private static String normalize(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return value.trim();
  }
}
