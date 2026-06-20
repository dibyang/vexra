package net.xdob.vexra.adb.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADB 试生产准入报告归档器测试。
 *
 * <p>确保准入结论以稳定 properties 形式写入，后续 CI、doctor 和人工审批可以直接复用。</p>
 */
class AdbTrialProductionAdmissionWriterTest {
  @TempDir
  Path tempDir;

  /**
   * 验证准入报告和失败原因会被归档。
   *
   * @throws Exception 文件写入失败时抛出
   */
  @Test
  void shouldWriteAdmissionProperties() throws Exception {
    AdbTrialProductionAdmissionReport report =
        new AdbTrialProductionAdmissionReport("rel-001", "0.7.0",
            true, true, false, true, true, false, "pilot window");
    AdbLongRunStressEvaluation evaluation =
        new AdbTrialProductionAdmissionGate().evaluate(report);

    Path file = new AdbTrialProductionAdmissionWriter().write(tempDir, report,
        evaluation);
    Properties properties = load(file);

    assertEquals("trial-production-admission.properties",
        file.getFileName().toString());
    assertEquals("rel-001", properties.getProperty("releaseId"));
    assertEquals("false", properties.getProperty("admitted"));
    assertEquals("false", properties.getProperty("rollbackPlanReady"));
    assertEquals("pilot window", properties.getProperty("notes"));
    assertTrue(properties.getProperty("failureReasons")
        .contains("rollback plan is not ready"));
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
