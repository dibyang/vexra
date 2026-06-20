package net.xdob.vexra.adb.db;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * ADB 进程级恢复演练报告。
 *
 * <p>报告把 kill leader、kill follower、kill witness 和全集群重启的结果聚合成同一个
 * release gate 输入。真实执行器可以逐步完善，但报告格式先固定，方便后续证据归档。</p>
 */
public final class AdbRecoveryDrillReport {
  private final String drillName;
  private final List<AdbRecoveryDrillResult> results;

  /**
   * 创建进程级恢复演练报告。
   *
   * @param drillName 演练名称或执行批次名称
   * @param results 每个恢复演练场景的结果
   */
  public AdbRecoveryDrillReport(String drillName,
      List<AdbRecoveryDrillResult> results) {
    this.drillName = normalize(drillName, "drillName");
    this.results = immutableResults(results);
  }

  public String getDrillName() {
    return drillName;
  }

  public List<AdbRecoveryDrillResult> getResults() {
    return results;
  }

  private static List<AdbRecoveryDrillResult> immutableResults(
      List<AdbRecoveryDrillResult> values) {
    Objects.requireNonNull(values, "results == null");
    List<AdbRecoveryDrillResult> copy = new ArrayList<>();
    for (AdbRecoveryDrillResult value : values) {
      copy.add(Objects.requireNonNull(value, "result is null"));
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
