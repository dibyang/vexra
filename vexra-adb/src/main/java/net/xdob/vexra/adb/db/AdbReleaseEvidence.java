package net.xdob.vexra.adb.db;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * ADB 发布门禁证据。
 *
 * <p>该对象把 GA-07 release gate 的核心输入和输出固定成一个可归档模型：版本、端到端
 * 集群报告、门禁评估、验证命令和 checksum。它不执行测试，只作为 CI、脚本和人工审计之间
 * 传递发布证据的边界对象。</p>
 */
public final class AdbReleaseEvidence {
  private final String releaseId;
  private final String version;
  private final AdbEndToEndClusterStressReport report;
  private final AdbLongRunStressEvaluation evaluation;
  private final List<String> commands;
  private final List<String> checksums;

  /**
   * 创建发布门禁证据。
   *
   * @param releaseId 发布或试生产批次 ID
   * @param version 本次发布版本
   * @param report 端到端集群报告
   * @param evaluation release gate 评估结果
   * @param commands 关键验证命令
   * @param checksums checksum 摘要
   */
  public AdbReleaseEvidence(String releaseId, String version,
      AdbEndToEndClusterStressReport report,
      AdbLongRunStressEvaluation evaluation, List<String> commands,
      List<String> checksums) {
    this.releaseId = normalize(releaseId, "releaseId");
    this.version = normalize(version, "version");
    this.report = Objects.requireNonNull(report, "report == null");
    this.evaluation = Objects.requireNonNull(evaluation,
        "evaluation == null");
    this.commands = immutableStrings(commands, "commands");
    this.checksums = immutableStrings(checksums, "checksums");
  }

  public String getReleaseId() {
    return releaseId;
  }

  public String getVersion() {
    return version;
  }

  public AdbEndToEndClusterStressReport getReport() {
    return report;
  }

  public AdbLongRunStressEvaluation getEvaluation() {
    return evaluation;
  }

  public List<String> getCommands() {
    return commands;
  }

  public List<String> getChecksums() {
    return checksums;
  }

  private static List<String> immutableStrings(List<String> values,
      String fieldName) {
    Objects.requireNonNull(values, fieldName + " == null");
    List<String> copy = new ArrayList<>();
    for (String value : values) {
      copy.add(normalize(value, fieldName + " value"));
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
