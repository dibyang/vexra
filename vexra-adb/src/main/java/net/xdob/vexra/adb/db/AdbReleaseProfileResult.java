package net.xdob.vexra.adb.db;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * ADB release profile 执行结果。
 *
 * <p>结果由 `AdbReleaseProfileRunner` 返回给 CI、Gradle task 或人工脚本。它同时包含
 * 门禁是否通过、evidence 文件路径和失败原因，调用方无需再解析 properties 文件即可决定
 * 是否阻断发布。</p>
 */
public final class AdbReleaseProfileResult {
  private final AdbLongRunStressEvaluation evaluation;
  private final Path evidenceFile;
  private final Path trialAdmissionFile;

  /**
   * 创建 release profile 执行结果。
   *
   * @param evaluation release gate 评估结果
   * @param evidenceFile 已写入的 evidence 文件路径
   */
  public AdbReleaseProfileResult(AdbLongRunStressEvaluation evaluation,
      Path evidenceFile) {
    this(evaluation, evidenceFile, null);
  }

  /**
   * 创建 release profile 执行结果。
   *
   * @param evaluation release gate 评估结果
   * @param evidenceFile 已写入的 evidence 文件路径
   * @param trialAdmissionFile 已写入的试生产准入文件路径；未生成时为 null
   */
  public AdbReleaseProfileResult(AdbLongRunStressEvaluation evaluation,
      Path evidenceFile, Path trialAdmissionFile) {
    this.evaluation = Objects.requireNonNull(evaluation,
        "evaluation == null");
    this.evidenceFile = Objects.requireNonNull(evidenceFile,
        "evidenceFile == null");
    this.trialAdmissionFile = trialAdmissionFile;
  }

  public boolean isPassed() {
    return evaluation.isPassed();
  }

  public List<String> getFailureReasons() {
    return evaluation.getFailureReasons();
  }

  public Path getEvidenceFile() {
    return evidenceFile;
  }

  public AdbLongRunStressEvaluation getEvaluation() {
    return evaluation;
  }

  public Path getTrialAdmissionFile() {
    return trialAdmissionFile;
  }
}
