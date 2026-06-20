package net.xdob.vexra.adb.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADB 诊断包写入器测试。
 *
 * <p>诊断包会被用于故障现场归档，测试重点验证输出稳定且不会泄漏 token、证书、
 * 私钥和权限配置路径等敏感配置。</p>
 */
class AdbDiagnosticBundleWriterTest {
  @TempDir
  Path tempDir;

  /**
   * 验证配置脱敏和诊断包文件写出。
   *
   * @throws Exception 写文件或读文件失败时抛出
   */
  @Test
  void shouldWriteRedactedDiagnosticBundle() throws Exception {
    Properties properties = new Properties();
    properties.setProperty("adb.cluster.group", "group-1");
    properties.setProperty("adb.security.token", "token-value");
    properties.setProperty("adb.security.tls.cert", "cert-value");
    properties.setProperty("adb.cluster.node.n1.privilegeConfig",
        "secret-privileges.json");

    Map<String, Number> metrics = new LinkedHashMap<>();
    metrics.put("adb_doctor_preflight_passed", 1);
    metrics.put("adb_sql_slow_sql_count", 1);
    metrics.put("adb_worker_gc_deleted_versions", 2);
    Map<String, String> operations = new LinkedHashMap<>();
    operations.put("preflightPassed", "true");
    operations.put("sql.recentSlowSql.0",
        "timestampMillis=1,sqlType=SELECT,table=T,latencyMillis=120");
    operations.put("worker.gc.deletedVersions", "2");
    AdbDiagnosticBundle bundle = new AdbDiagnosticBundle("bundle-1", 123L,
        "0.1", "2.3.0", "0.6.0",
        AdbDiagnosticBundleWriter.redact(properties),
        operations,
        metrics,
        Collections.singletonMap("sql.log",
            Arrays.asList("line-2", "line-3")),
        Arrays.asList("PASS", "OK topology=2data1witness"),
        Collections.singletonList("unit test"));

    Path file = new AdbDiagnosticBundleWriter().write(bundle, tempDir);
    String text = new String(Files.readAllBytes(file),
        StandardCharsets.UTF_8);

    assertTrue(text.contains("bundleId=bundle-1"));
    assertTrue(text.contains("adb.cluster.group=group-1"));
    assertTrue(text.contains("sql.recentSlowSql.0=timestampMillis=1"));
    assertTrue(text.contains("adb_sql_slow_sql_count=1"));
    assertTrue(text.contains("worker.gc.deletedVersions=2"));
    assertTrue(text.contains("adb_worker_gc_deleted_versions=2"));
    assertTrue(text.contains("adb.security.token=<redacted>"));
    assertTrue(text.contains("adb.security.tls.cert=<redacted>"));
    assertTrue(text.contains(
        "adb.cluster.node.n1.privilegeConfig=<redacted>"));
    assertTrue(text.contains("--- sql.log"));
    assertTrue(text.contains("line-3"));
    assertFalse(text.contains("token-value"));
    assertFalse(text.contains("cert-value"));
    assertFalse(text.contains("secret-privileges.json"));
  }
}
