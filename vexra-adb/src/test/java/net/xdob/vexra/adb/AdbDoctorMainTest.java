package net.xdob.vexra.adb;

import net.xdob.vexra.adb.db.AdbDiagnosticBundleWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADB doctor 命令入口测试。
 *
 * <p>该测试通过真实 main 方法读取集群配置并生成诊断包，覆盖命令参数、预检集成、
 * 脱敏输出和离线故障现场可重复执行的基础路径。</p>
 */
class AdbDoctorMainTest {
  @TempDir
  Path tempDir;

  /**
   * 验证 doctor 可以生成脱敏诊断包。
   *
   * @throws Exception 配置写入或命令执行失败时抛出
   */
  @Test
  void shouldGenerateRedactedBundle() throws Exception {
    Path config = writeClusterConfig();
    Path log = tempDir.resolve("sql.log");
    Files.write(log, java.util.Arrays.asList("line-1", "line-2", "line-3"),
        StandardCharsets.UTF_8);
    Path evidence = writeProperties("release-evidence.properties",
        "releaseId", "rel-001", "passed", "true",
        "checksums", "backup=1;restore=1");
    Path operationReport = writeProperties("operation.properties",
        "operation", "upgrade", "status", "complete",
        "operator.token", "hidden-token");
    Path output = tempDir.resolve("doctor-output");

    AdbDoctorMain.main(new String[] {
        "--config", config.toString(),
        "--output", output.toString(),
        "--bundleId", "doctor-test",
        "--version", "test-version",
        "--h2dbVersion", "2.3.0",
        "--ldbVersion", "0.6.0",
        "--checkRuntimeScripts", "false",
        "--logs", log.toString(),
        "--logTailLines", "2",
        "--evidence", evidence.toString(),
        "--operationReports", operationReport.toString()
    });

    Path file = output.resolve(AdbDiagnosticBundleWriter.BUNDLE_FILE);
    String text = new String(Files.readAllBytes(file),
        StandardCharsets.UTF_8);
    assertTrue(text.contains("bundleId=doctor-test"));
    assertTrue(text.contains("productVersion=test-version"));
    assertTrue(text.contains("h2dbVersion=2.3.0"));
    assertTrue(text.contains("ldbVersion=0.6.0"));
    assertTrue(text.contains("PASS"));
    assertTrue(text.contains("preflightPassed=true"));
    assertTrue(text.contains("adb.security.token=<redacted>"));
    assertTrue(text.contains("--- " + log.toAbsolutePath()));
    assertFalse(text.contains("line-1"));
    assertTrue(text.contains("line-2"));
    assertTrue(text.contains("line-3"));
    assertTrue(text.contains("releaseEvidence.0.releaseId=rel-001"));
    assertTrue(text.contains(
        "releaseEvidence.0.checksums=backup=1;restore=1"));
    assertTrue(text.contains("operationReport.0.operation=upgrade"));
    assertTrue(text.contains("operationReport.0.status=complete"));
    assertTrue(text.contains("operationReport.0.operator_token=<redacted>"));
    assertFalse(text.contains("token-secret"));
    assertFalse(text.contains("hidden-token"));
  }

  private Path writeClusterConfig() throws Exception {
    Path config = tempDir.resolve("cluster.properties");
    Files.write(config, java.util.Arrays.asList(
        "adb.security.tls.enabled=true",
        "adb.security.auth.enabled=true",
        "adb.security.token=token-secret",
        "adb.cluster.runtimeDir=" + path(tempDir.resolve("runtime")),
        "adb.cluster.group=11111111-1111-1111-1111-111111111111",
        "adb.cluster.nodes=n1,n2,n3",
        "adb.cluster.sql.port=9123",
        "adb.cluster.sql.baseDir=" + path(tempDir.resolve("sql")),
        "adb.cluster.catalog.path="
            + path(tempDir.resolve("run/adb-catalog.properties")),
        "adb.cluster.node.n1.host=127.0.0.1",
        "adb.cluster.node.n1.port=19001",
        "adb.cluster.node.n1.dataDir=" + path(tempDir.resolve("n1")),
        "adb.cluster.node.n1.role=DATA_NODE",
        "adb.cluster.node.n2.host=127.0.0.1",
        "adb.cluster.node.n2.port=19002",
        "adb.cluster.node.n2.dataDir=" + path(tempDir.resolve("n2")),
        "adb.cluster.node.n2.role=DATA_NODE",
        "adb.cluster.node.n3.host=127.0.0.1",
        "adb.cluster.node.n3.port=19003",
        "adb.cluster.node.n3.dataDir=" + path(tempDir.resolve("n3")),
        "adb.cluster.node.n3.role=WITNESS_NODE",
        "adb.catalog.tso.readTs=20000",
        "adb.catalog.table.TEST.id=1",
        "adb.catalog.table.TEST.epoch=0"), StandardCharsets.UTF_8);
    return config;
  }

  private static String path(Path path) {
    return path.toAbsolutePath().toString().replace('\\', '/');
  }

  private Path writeProperties(String name, String... pairs) throws Exception {
    Properties properties = new Properties();
    for (int i = 0; i < pairs.length; i += 2) {
      properties.setProperty(pairs[i], pairs[i + 1]);
    }
    Path file = tempDir.resolve(name);
    try (OutputStream output = Files.newOutputStream(file)) {
      properties.store(output, "test");
    }
    return file;
  }
}
