package net.xdob.vexra.adb.db;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * ADB 端到端集群压测报告。
 *
 * <p>该报告把已有长稳压测指标扩展到 Run-12 的产品化门禁：除了吞吐、失败率、延迟和故障
 * 注入矩阵，还必须证明 SQL/region 读写、commit 崩溃注入、恢复演练和滚动升级演练都通过。</p>
 */
public final class AdbEndToEndClusterStressReport {
  private final String clusterName;
  private final AdbLongRunStressReport longRunReport;
  private final AdbLongRunStressEvaluation commitCrashEvaluation;
  private final AdbLongRunStressEvaluation recoveryDrillEvaluation;
  private final boolean clusterReadWritePassed;
  private final boolean recoveryDrillPassed;
  private final boolean rollingUpgradePassed;
  private final int sqlRegionSmokeCycles;

  public AdbEndToEndClusterStressReport(String clusterName,
      AdbLongRunStressReport longRunReport, boolean clusterReadWritePassed,
      boolean recoveryDrillPassed, boolean rollingUpgradePassed,
      int sqlRegionSmokeCycles) {
    this(clusterName, longRunReport, missingCommitCrashEvaluation(),
        missingRecoveryDrillEvaluation(), clusterReadWritePassed,
        recoveryDrillPassed, rollingUpgradePassed, sqlRegionSmokeCycles);
  }

  /**
   * 创建端到端集群压测报告。
   *
   * @param clusterName 集群名称
   * @param longRunReport 长稳压测报告
   * @param commitCrashEvaluation commit 崩溃注入门禁结果
   * @param clusterReadWritePassed SQL/region 读写 smoke 是否通过
   * @param recoveryDrillPassed 恢复演练是否通过
   * @param rollingUpgradePassed 滚动升级演练是否通过
   * @param sqlRegionSmokeCycles SQL/region smoke 循环次数
   */
  public AdbEndToEndClusterStressReport(String clusterName,
      AdbLongRunStressReport longRunReport,
      AdbLongRunStressEvaluation commitCrashEvaluation,
      boolean clusterReadWritePassed, boolean recoveryDrillPassed,
      boolean rollingUpgradePassed, int sqlRegionSmokeCycles) {
    this(clusterName, longRunReport, commitCrashEvaluation,
        missingRecoveryDrillEvaluation(), clusterReadWritePassed,
        recoveryDrillPassed, rollingUpgradePassed, sqlRegionSmokeCycles);
  }

  /**
   * 创建端到端集群压测报告。
   *
   * @param clusterName 集群名称
   * @param longRunReport 长稳压测报告
   * @param commitCrashEvaluation commit 崩溃注入门禁结果
   * @param recoveryDrillEvaluation kill/restart 恢复演练门禁结果
   * @param clusterReadWritePassed SQL/region 读写 smoke 是否通过
   * @param recoveryDrillPassed 恢复演练是否通过
   * @param rollingUpgradePassed 滚动升级演练是否通过
   * @param sqlRegionSmokeCycles SQL/region smoke 循环次数
   */
  public AdbEndToEndClusterStressReport(String clusterName,
      AdbLongRunStressReport longRunReport,
      AdbLongRunStressEvaluation commitCrashEvaluation,
      AdbLongRunStressEvaluation recoveryDrillEvaluation,
      boolean clusterReadWritePassed, boolean recoveryDrillPassed,
      boolean rollingUpgradePassed, int sqlRegionSmokeCycles) {
    this.clusterName = normalize(clusterName, "clusterName");
    this.longRunReport = Objects.requireNonNull(longRunReport,
        "longRunReport == null");
    this.commitCrashEvaluation = Objects.requireNonNull(
        commitCrashEvaluation, "commitCrashEvaluation == null");
    this.recoveryDrillEvaluation = Objects.requireNonNull(
        recoveryDrillEvaluation, "recoveryDrillEvaluation == null");
    this.clusterReadWritePassed = clusterReadWritePassed;
    this.recoveryDrillPassed = recoveryDrillPassed;
    this.rollingUpgradePassed = rollingUpgradePassed;
    if (sqlRegionSmokeCycles < 0) {
      throw new IllegalArgumentException("sqlRegionSmokeCycles is negative");
    }
    this.sqlRegionSmokeCycles = sqlRegionSmokeCycles;
  }

  public String getClusterName() {
    return clusterName;
  }

  public AdbLongRunStressReport getLongRunReport() {
    return longRunReport;
  }

  public AdbLongRunStressEvaluation getCommitCrashEvaluation() {
    return commitCrashEvaluation;
  }

  public AdbLongRunStressEvaluation getRecoveryDrillEvaluation() {
    return recoveryDrillEvaluation;
  }

  public boolean isClusterReadWritePassed() {
    return clusterReadWritePassed;
  }

  public boolean isRecoveryDrillPassed() {
    return recoveryDrillPassed;
  }

  public boolean isRollingUpgradePassed() {
    return rollingUpgradePassed;
  }

  public int getSqlRegionSmokeCycles() {
    return sqlRegionSmokeCycles;
  }

  private static String normalize(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return value.trim();
  }

  private static AdbLongRunStressEvaluation missingCommitCrashEvaluation() {
    return missingEvaluation("commit crash injection gate is missing");
  }

  private static AdbLongRunStressEvaluation missingRecoveryDrillEvaluation() {
    return missingEvaluation("recovery drill gate is missing");
  }

  private static AdbLongRunStressEvaluation missingEvaluation(String reason) {
    List<String> reasons = new ArrayList<>();
    reasons.add(reason);
    return new AdbLongRunStressEvaluation(reasons);
  }
}
