package net.xdob.vexra.adb.db;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * ADB release profile 执行器。
 *
 * <p>执行器把 GA-07 的端到端门禁和 evidence 归档串成一个稳定入口：先评估
 * `AdbEndToEndClusterStressReport`，再无论通过或失败都写入 release evidence。它不直接
 * 启动压测或故障注入，后续 Gradle task / CI 只需要把已经生成的报告交给该类。</p>
 */
public final class AdbReleaseProfileRunner {
  private final AdbEndToEndClusterStressGate gate;
  private final AdbReleaseEvidenceWriter evidenceWriter;

  /**
   * 创建默认 release profile 执行器。
   */
  public AdbReleaseProfileRunner() {
    this(new AdbEndToEndClusterStressGate(), new AdbReleaseEvidenceWriter());
  }

  /**
   * 创建 release profile 执行器。
   *
   * @param gate 端到端 release gate
   * @param evidenceWriter evidence 归档器
   */
  public AdbReleaseProfileRunner(AdbEndToEndClusterStressGate gate,
      AdbReleaseEvidenceWriter evidenceWriter) {
    this.gate = Objects.requireNonNull(gate, "gate == null");
    this.evidenceWriter = Objects.requireNonNull(evidenceWriter,
        "evidenceWriter == null");
  }

  /**
   * 执行 release profile 并写入 evidence。
   *
   * @param request release profile 请求
   * @return release profile 执行结果
   * @throws IOException evidence 目录创建或文件写入失败时抛出
   */
  public AdbReleaseProfileResult run(AdbReleaseProfileRequest request)
      throws IOException {
    Objects.requireNonNull(request, "request == null");
    AdbLongRunStressEvaluation evaluation =
        gate.evaluate(request.getReport(), request.getCriteria());
    AdbReleaseEvidence evidence = new AdbReleaseEvidence(
        request.getReleaseId(), request.getVersion(), request.getReport(),
        evaluation, request.getCommands(), request.getChecksums());
    Path evidenceFile = evidenceWriter.write(request.getOutputDirectory(),
        evidence);
    return new AdbReleaseProfileResult(evaluation, evidenceFile);
  }

  /**
   * ADB release profile 请求。
   *
   * <p>请求对象显式携带 release id、版本、报告、验收标准、命令、checksum 和输出目录，
   * 让 runner 不依赖系统属性或进程环境，便于测试和后续 CLI 封装。</p>
   */
  public static final class AdbReleaseProfileRequest {
    private final String releaseId;
    private final String version;
    private final AdbEndToEndClusterStressReport report;
    private final AdbLongRunAcceptanceCriteria criteria;
    private final List<String> commands;
    private final List<String> checksums;
    private final Path outputDirectory;

    /**
     * 创建 release profile 请求。
     *
     * @param releaseId 发布或试生产批次 ID
     * @param version 发布版本
     * @param report 端到端集群报告
     * @param criteria 长稳验收标准
     * @param commands 关键验证命令
     * @param checksums checksum 摘要
     * @param outputDirectory evidence 输出目录
     */
    public AdbReleaseProfileRequest(String releaseId, String version,
        AdbEndToEndClusterStressReport report,
        AdbLongRunAcceptanceCriteria criteria, List<String> commands,
        List<String> checksums, Path outputDirectory) {
      this.releaseId = requireText(releaseId, "releaseId");
      this.version = requireText(version, "version");
      this.report = Objects.requireNonNull(report, "report == null");
      this.criteria = Objects.requireNonNull(criteria, "criteria == null");
      this.commands = Objects.requireNonNull(commands, "commands == null");
      this.checksums = Objects.requireNonNull(checksums, "checksums == null");
      this.outputDirectory = Objects.requireNonNull(outputDirectory,
          "outputDirectory == null");
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

    public AdbLongRunAcceptanceCriteria getCriteria() {
      return criteria;
    }

    public List<String> getCommands() {
      return commands;
    }

    public List<String> getChecksums() {
      return checksums;
    }

    public Path getOutputDirectory() {
      return outputDirectory;
    }

    private static String requireText(String value, String fieldName) {
      if (value == null || value.trim().isEmpty()) {
        throw new IllegalArgumentException(fieldName + " is empty");
      }
      return value.trim();
    }
  }
}
