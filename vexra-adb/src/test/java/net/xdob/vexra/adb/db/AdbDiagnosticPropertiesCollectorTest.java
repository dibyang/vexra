package net.xdob.vexra.adb.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ADB 诊断 properties 采集器测试。
 *
 * <p>该采集器负责把 release evidence 和运维报告接入 doctor 诊断包，测试覆盖
 * 字段命名、缺失文件记录和敏感字段脱敏。</p>
 */
class AdbDiagnosticPropertiesCollectorTest {
  @TempDir
  Path tempDir;

  /**
   * 验证 properties 字段被稳定映射并脱敏。
   *
   * @throws Exception 写入或读取 properties 失败时抛出
   */
  @Test
  void shouldCollectAndRedactProperties() throws Exception {
    Path file = tempDir.resolve("release-evidence.properties");
    Properties properties = new Properties();
    properties.setProperty("releaseId", "rel-001");
    properties.setProperty("checksums", "backup=1;restore=1");
    properties.setProperty("auth.token", "secret-token");
    try (OutputStream output = Files.newOutputStream(file)) {
      properties.store(output, "test");
    }

    Map<String, String> values = new AdbDiagnosticPropertiesCollector()
        .collect(Collections.singletonList(file), "releaseEvidence");

    assertEquals("loaded", values.get("releaseEvidence.0.status"));
    assertEquals("rel-001", values.get("releaseEvidence.0.releaseId"));
    assertEquals("backup=1;restore=1",
        values.get("releaseEvidence.0.checksums"));
    assertEquals("<redacted>", values.get("releaseEvidence.0.auth_token"));
  }

  /**
   * 验证缺失文件会被记录为 missing，而不是让 doctor 失败。
   *
   * @throws Exception 采集失败时抛出
   */
  @Test
  void shouldReportMissingPropertiesFile() throws Exception {
    Path missing = tempDir.resolve("missing.properties");

    Map<String, String> values = new AdbDiagnosticPropertiesCollector()
        .collect(Collections.singletonList(missing), "operationReport");

    assertEquals("missing", values.get("operationReport.0.status"));
    assertEquals(missing.toAbsolutePath().toString(),
        values.get("operationReport.0.path"));
  }
}
