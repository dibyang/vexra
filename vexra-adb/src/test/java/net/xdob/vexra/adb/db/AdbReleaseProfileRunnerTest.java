package net.xdob.vexra.adb.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADB release profile 执行器测试。
 *
 * <p>测试覆盖 GA-07 的核心链路：报告进入 release gate，runner 生成 evidence，并把通过或
 * 失败结果返回给上层 CI。</p>
 */
class AdbReleaseProfileRunnerTest {
  @TempDir
  Path tempDir;

  /**
   * 验证通过的 release profile 会返回通过并写入 evidence。
   */
  @Test
  void shouldRunPassingReleaseProfileAndArchiveEvidence() throws Exception {
    AdbReleaseProfileRunner.AdbReleaseProfileRequest request =
        request("rel-pass", report(3), tempDir.resolve("pass"));

    AdbReleaseProfileResult result =
        new AdbReleaseProfileRunner().run(request);
    Properties properties = load(result.getEvidenceFile());

    assertTrue(result.isPassed());
    assertTrue(result.getFailureReasons().isEmpty());
    assertEquals("true", properties.getProperty("passed"));
    assertEquals("rel-pass", properties.getProperty("releaseId"));
  }

  /**
   * 验证失败的 release profile 仍然写入 evidence，便于排查和审计。
   */
  @Test
  void shouldArchiveEvidenceWhenReleaseProfileFails() throws Exception {
    AdbReleaseProfileRunner.AdbReleaseProfileRequest request =
        request("rel-fail", report(0), tempDir.resolve("fail"));

    AdbReleaseProfileResult result =
        new AdbReleaseProfileRunner().run(request);
    Properties properties = load(result.getEvidenceFile());

    assertFalse(result.isPassed());
    assertTrue(result.getFailureReasons().contains(
        "sql/region smoke cycle is missing"));
    assertEquals("false", properties.getProperty("passed"));
    assertEquals("sql/region smoke cycle is missing",
        properties.getProperty("failureReasons"));
  }

  private static AdbReleaseProfileRunner.AdbReleaseProfileRequest request(
      String releaseId, AdbEndToEndClusterStressReport report,
      Path outputDirectory) {
    return new AdbReleaseProfileRunner.AdbReleaseProfileRequest(releaseId,
        "0.7.0", report, criteria(),
        Collections.singletonList(".\\gradlew.bat :vexra-adb:test"),
        Arrays.asList("backup=abc123", "restore=abc123"), outputDirectory);
  }

  private static AdbEndToEndClusterStressReport report(int smokeCycles) {
    AdbLongRunStressEvaluation passed =
        new AdbLongRunStressEvaluation(Collections.<String>emptyList());
    return new AdbEndToEndClusterStressReport("ga-cluster", longRunReport(),
        passed, passed, true, true, true, smokeCycles);
  }

  private static AdbLongRunAcceptanceCriteria criteria() {
    return new AdbLongRunAcceptanceCriteria(5_000, 5_000, 0.01D, 500,
        1, 1, 1, EnumSet.allOf(AdbFaultInjectionType.class));
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

  private static Properties load(Path file) throws Exception {
    assertTrue(Files.exists(file));
    Properties properties = new Properties();
    try (InputStream input = Files.newInputStream(file)) {
      properties.load(input);
    }
    return properties;
  }
}
