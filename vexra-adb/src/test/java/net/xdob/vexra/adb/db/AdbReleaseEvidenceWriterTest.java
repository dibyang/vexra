package net.xdob.vexra.adb.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADB 发布证据归档器测试。
 *
 * <p>测试覆盖 GA-07 release evidence 目录的最小行为：自动创建目录、写入稳定字段、
 * 保留失败原因，并在目标路径不是目录时快速失败。</p>
 */
class AdbReleaseEvidenceWriterTest {
  @TempDir
  Path tempDir;

  /**
   * 验证通过的 release gate 可以写入结构化 evidence 文件。
   */
  @Test
  void shouldWriteReleaseEvidenceProperties() throws Exception {
    AdbReleaseEvidence evidence = evidence("rel-001", "0.7.0",
        passedEvaluation());

    Path file = new AdbReleaseEvidenceWriter().write(
        tempDir.resolve("evidence").resolve("rel-001"), evidence);
    Properties properties = load(file);

    assertEquals("release-evidence.properties",
        file.getFileName().toString());
    assertEquals("rel-001", properties.getProperty("releaseId"));
    assertEquals("0.7.0", properties.getProperty("version"));
    assertEquals("ga-cluster", properties.getProperty("clusterName"));
    assertEquals("true", properties.getProperty("passed"));
    assertEquals("", properties.getProperty("failureReasons"));
    assertEquals(".\\gradlew.bat :vexra-adb:test",
        properties.getProperty("commands"));
    assertEquals("backup=abc123;restore=abc123",
        properties.getProperty("checksums"));
    assertEquals("ga-e2e", properties.getProperty("workloadName"));
    assertEquals("3", properties.getProperty("sqlRegionSmokeCycles"));
  }

  /**
   * 验证失败原因会被归档，便于 CI 和人工复核。
   */
  @Test
  void shouldArchiveFailureReasons() throws Exception {
    AdbLongRunStressEvaluation failed =
        new AdbLongRunStressEvaluation(Arrays.asList("p99 latency exceeds limit",
            "recovery drill failed"));
    Path file = new AdbReleaseEvidenceWriter().write(tempDir,
        evidence("rel-002", "0.7.1", failed));
    Properties properties = load(file);

    assertEquals("false", properties.getProperty("passed"));
    assertEquals("p99 latency exceeds limit;recovery drill failed",
        properties.getProperty("failureReasons"));
  }

  /**
   * 验证输出路径已是普通文件时快速失败。
   */
  @Test
  void shouldRejectFileAsOutputDirectory() throws Exception {
    Path file = tempDir.resolve("not-dir");
    Files.write(file, Collections.singletonList("occupied"));

    assertThrows(IOException.class,
        () -> new AdbReleaseEvidenceWriter().write(file,
            evidence("rel-003", "0.7.2", passedEvaluation())));
  }

  private static AdbReleaseEvidence evidence(String releaseId, String version,
      AdbLongRunStressEvaluation evaluation) {
    return new AdbReleaseEvidence(releaseId, version, report(), evaluation,
        Collections.singletonList(".\\gradlew.bat :vexra-adb:test"),
        Arrays.asList("backup=abc123", "restore=abc123"));
  }

  private static AdbEndToEndClusterStressReport report() {
    return new AdbEndToEndClusterStressReport("ga-cluster", longRunReport(),
        passedEvaluation(), passedEvaluation(), true, true, true, 3);
  }

  private static AdbLongRunStressEvaluation passedEvaluation() {
    return new AdbLongRunStressEvaluation(Collections.<String>emptyList());
  }

  private static AdbLongRunStressReport longRunReport() {
    return new AdbLongRunStressReport("ga-e2e", 10_000, 10_000, 0,
        1_000D, 20, 50, 2, 1, 3, Arrays.asList(
        recovered(AdbFaultInjectionType.NETWORK_PARTITION),
        recovered(AdbFaultInjectionType.LEADER_TRANSFER),
        recovered(AdbFaultInjectionType.DISK_FAULT),
        recovered(AdbFaultInjectionType.NODE_RESTART),
        recovered(AdbFaultInjectionType.WITNESS_LOSS)));
  }

  private static AdbFaultInjectionResult recovered(
      AdbFaultInjectionType type) {
    return new AdbFaultInjectionResult(type, 1, 1, true, "recovered");
  }

  private static Properties load(Path file) throws IOException {
    assertTrue(Files.exists(file));
    Properties properties = new Properties();
    try (InputStream input = Files.newInputStream(file)) {
      properties.load(input);
    }
    return properties;
  }
}
