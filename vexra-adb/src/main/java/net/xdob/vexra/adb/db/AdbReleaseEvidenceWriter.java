package net.xdob.vexra.adb.db;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * ADB 发布证据归档器。
 *
 * <p>归档器把 `AdbReleaseEvidence` 写成稳定的 `release-evidence.properties` 文件。
 * 它只写结构化摘要，不复制日志或敏感配置；后续 diagnostic bundle 可以在同一目录中追加
 * 脱敏日志、指标和配置快照。</p>
 */
public final class AdbReleaseEvidenceWriter {
  public static final String EVIDENCE_FILE_NAME = "release-evidence.properties";

  /**
   * 写入发布证据。
   *
   * @param outputDirectory evidence 输出目录
   * @param evidence 发布证据
   * @return 写入的 evidence 文件路径
   * @throws IOException 目录创建或文件写入失败时抛出
   */
  public Path write(Path outputDirectory, AdbReleaseEvidence evidence)
      throws IOException {
    if (outputDirectory == null) {
      throw new NullPointerException("outputDirectory == null");
    }
    if (evidence == null) {
      throw new NullPointerException("evidence == null");
    }
    if (Files.exists(outputDirectory) && !Files.isDirectory(outputDirectory)) {
      throw new IOException("release evidence path is not a directory: "
          + outputDirectory);
    }
    Files.createDirectories(outputDirectory);
    Path file = outputDirectory.resolve(EVIDENCE_FILE_NAME);
    Properties properties = toProperties(evidence);
    try (OutputStream output = Files.newOutputStream(file)) {
      properties.store(output, "ADB release evidence");
    }
    return file;
  }

  private static Properties toProperties(AdbReleaseEvidence evidence) {
    Properties properties = new Properties();
    AdbEndToEndClusterStressReport report = evidence.getReport();
    AdbLongRunStressReport longRun = report.getLongRunReport();
    properties.setProperty("releaseId", evidence.getReleaseId());
    properties.setProperty("version", evidence.getVersion());
    properties.setProperty("clusterName", report.getClusterName());
    properties.setProperty("passed", String.valueOf(
        evidence.getEvaluation().isPassed()));
    properties.setProperty("failureReasons", join(
        evidence.getEvaluation().getFailureReasons()));
    properties.setProperty("commands", join(evidence.getCommands()));
    properties.setProperty("checksums", join(evidence.getChecksums()));
    properties.setProperty("clusterReadWritePassed", String.valueOf(
        report.isClusterReadWritePassed()));
    properties.setProperty("recoveryDrillPassed", String.valueOf(
        report.isRecoveryDrillPassed()));
    properties.setProperty("rollingUpgradePassed", String.valueOf(
        report.isRollingUpgradePassed()));
    properties.setProperty("commitCrashGatePassed", String.valueOf(
        report.getCommitCrashEvaluation().isPassed()));
    properties.setProperty("commitCrashFailureReasons", join(
        report.getCommitCrashEvaluation().getFailureReasons()));
    properties.setProperty("recoveryDrillGatePassed", String.valueOf(
        report.getRecoveryDrillEvaluation().isPassed()));
    properties.setProperty("recoveryDrillFailureReasons", join(
        report.getRecoveryDrillEvaluation().getFailureReasons()));
    properties.setProperty("workloadName", longRun.getWorkloadName());
    properties.setProperty("durationMillis", String.valueOf(
        longRun.getDurationMillis()));
    properties.setProperty("totalOperations", String.valueOf(
        longRun.getTotalOperations()));
    properties.setProperty("failedOperations", String.valueOf(
        longRun.getFailedOperations()));
    properties.setProperty("p99LatencyMillis", String.valueOf(
        longRun.getP99LatencyMillis()));
    properties.setProperty("sqlRegionSmokeCycles", String.valueOf(
        report.getSqlRegionSmokeCycles()));
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
