package net.xdob.vexra.adb.db;

import java.util.Objects;

/**
 * ADB 单个进程级恢复演练结果。
 *
 * <p>结果记录某一类 kill/restart 演练是否真实执行、是否恢复以及演练前后数据校验是否一致。
 * 它是发布证据模型，不负责启动或停止进程。</p>
 */
public final class AdbRecoveryDrillResult {
  private final AdbRecoveryDrillScenario scenario;
  private final boolean attempted;
  private final boolean recovered;
  private final boolean checksumMatched;
  private final String notes;

  /**
   * 创建进程级恢复演练结果。
   *
   * @param scenario 演练场景
   * @param attempted 是否真实执行过演练
   * @param recovered 演练后读写、路由和多数派是否恢复
   * @param checksumMatched 演练前后数据 checksum 是否一致
   * @param notes 诊断说明、命令或日志路径
   */
  public AdbRecoveryDrillResult(AdbRecoveryDrillScenario scenario,
      boolean attempted, boolean recovered, boolean checksumMatched,
      String notes) {
    this.scenario = Objects.requireNonNull(scenario, "scenario == null");
    this.attempted = attempted;
    this.recovered = recovered;
    this.checksumMatched = checksumMatched;
    this.notes = notes == null ? "" : notes.trim();
  }

  public AdbRecoveryDrillScenario getScenario() {
    return scenario;
  }

  public boolean isAttempted() {
    return attempted;
  }

  public boolean isRecovered() {
    return recovered;
  }

  public boolean isChecksumMatched() {
    return checksumMatched;
  }

  public String getNotes() {
    return notes;
  }
}
