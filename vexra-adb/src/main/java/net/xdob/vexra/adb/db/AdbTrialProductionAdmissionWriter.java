package net.xdob.vexra.adb.db;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * ADB 试生产准入报告归档器。
 *
 * <p>归档器将准入报告和门禁结果写成稳定 properties 文件，供 CI、人工审批、
 * diagnostic bundle 和后续审计系统复用。</p>
 */
public final class AdbTrialProductionAdmissionWriter {
  public static final String ADMISSION_FILE_NAME =
      "trial-production-admission.properties";

  /**
   * 写入试生产准入报告。
   *
   * @param outputDirectory 输出目录
   * @param report 准入报告
   * @param evaluation 准入门禁评估结果
   * @return 写入的文件路径
   * @throws IOException 输出目录不可用或写入失败时抛出
   */
  public Path write(Path outputDirectory,
      AdbTrialProductionAdmissionReport report,
      AdbLongRunStressEvaluation evaluation) throws IOException {
    if (outputDirectory == null) {
      throw new NullPointerException("outputDirectory == null");
    }
    if (report == null) {
      throw new NullPointerException("report == null");
    }
    if (evaluation == null) {
      throw new NullPointerException("evaluation == null");
    }
    if (Files.exists(outputDirectory) && !Files.isDirectory(outputDirectory)) {
      throw new IOException("trial admission path is not a directory: "
          + outputDirectory);
    }
    Files.createDirectories(outputDirectory);
    Path file = outputDirectory.resolve(ADMISSION_FILE_NAME);
    try (OutputStream output = Files.newOutputStream(file)) {
      toProperties(report, evaluation).store(output,
          "ADB trial production admission");
    }
    return file;
  }

  private static Properties toProperties(
      AdbTrialProductionAdmissionReport report,
      AdbLongRunStressEvaluation evaluation) {
    Properties properties = new Properties();
    properties.setProperty("releaseId", report.getReleaseId());
    properties.setProperty("version", report.getVersion());
    properties.setProperty("admitted", String.valueOf(evaluation.isPassed()));
    properties.setProperty("failureReasons", join(
        evaluation.getFailureReasons()));
    properties.setProperty("releaseGatePassed", String.valueOf(
        report.isReleaseGatePassed()));
    properties.setProperty("dataScaleAccepted", String.valueOf(
        report.isDataScaleAccepted()));
    properties.setProperty("rollbackPlanReady", String.valueOf(
        report.isRollbackPlanReady()));
    properties.setProperty("alertingReady", String.valueOf(
        report.isAlertingReady()));
    properties.setProperty("onCallWindowReady", String.valueOf(
        report.isOnCallWindowReady()));
    properties.setProperty("knownLimitationsAccepted", String.valueOf(
        report.isKnownLimitationsAccepted()));
    properties.setProperty("notes", report.getNotes());
    return properties;
  }

  private static String join(Iterable<String> values) {
    StringBuilder builder = new StringBuilder();
    for (String value : values) {
      if (builder.length() > 0) {
        builder.append(';');
      }
      builder.append(value);
    }
    return builder.toString();
  }
}
