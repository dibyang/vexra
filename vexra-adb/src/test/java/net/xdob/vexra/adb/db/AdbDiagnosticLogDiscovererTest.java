package net.xdob.vexra.adb.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADB 诊断日志自动发现器测试。
 *
 * <p>测试覆盖基于集群编排配置的浅层日志发现，避免 doctor 需要用户手工列出每个
 * SQL server / region node 日志文件。</p>
 */
class AdbDiagnosticLogDiscovererTest {
  @TempDir
  Path tempDir;

  /**
   * 验证 runtime、SQL 和 node 目录中的常见日志文件会被发现。
   *
   * @throws Exception 文件创建或配置解析失败时抛出
   */
  @Test
  void shouldDiscoverLogsFromClusterConfig() throws Exception {
    Path runtimeLogs = tempDir.resolve("runtime/logs");
    Path sqlDir = tempDir.resolve("sql");
    Path nodeLogs = tempDir.resolve("n1/logs");
    Files.createDirectories(runtimeLogs);
    Files.createDirectories(sqlDir);
    Files.createDirectories(nodeLogs);
    Path runtimeLog = write(runtimeLogs.resolve("doctor.log"));
    Path sqlLog = write(sqlDir.resolve("sql-server.out"));
    Path nodeLog = write(nodeLogs.resolve("region.err"));
    write(sqlDir.resolve("ignore.txt"));

    List<Path> logs = new AdbDiagnosticLogDiscoverer(10).discover(config());

    assertTrue(logs.contains(runtimeLog.toAbsolutePath().normalize()));
    assertTrue(logs.contains(sqlLog.toAbsolutePath().normalize()));
    assertTrue(logs.contains(nodeLog.toAbsolutePath().normalize()));
    assertEquals(3, logs.size());
  }

  /**
   * 验证 maxFiles 会限制自动发现数量。
   *
   * @throws Exception 文件创建或配置解析失败时抛出
   */
  @Test
  void shouldLimitDiscoveredLogs() throws Exception {
    Path runtimeLogs = tempDir.resolve("runtime/logs");
    Files.createDirectories(runtimeLogs);
    write(runtimeLogs.resolve("a.log"));
    write(runtimeLogs.resolve("b.log"));

    List<Path> logs = new AdbDiagnosticLogDiscoverer(1).discover(config());

    assertEquals(1, logs.size());
  }

  private Path write(Path path) throws Exception {
    Files.createDirectories(path.getParent());
    Files.write(path, java.util.Collections.singletonList("log"),
        StandardCharsets.UTF_8);
    return path;
  }

  private AdbClusterOrchestrationConfig config() {
    Properties properties = new Properties();
    properties.setProperty("adb.cluster.runtimeDir",
        path(tempDir.resolve("runtime")));
    properties.setProperty("adb.cluster.group",
        "11111111-1111-1111-1111-111111111111");
    properties.setProperty("adb.cluster.nodes", "n1,n2,n3");
    properties.setProperty("adb.cluster.sql.port", "9123");
    properties.setProperty("adb.cluster.sql.baseDir",
        path(tempDir.resolve("sql")));
    properties.setProperty("adb.cluster.catalog.path",
        path(tempDir.resolve("run/adb-catalog.properties")));
    node(properties, "n1", 19001, "DATA_NODE");
    node(properties, "n2", 19002, "DATA_NODE");
    node(properties, "n3", 19003, "WITNESS_NODE");
    properties.setProperty("adb.catalog.tso.readTs", "20000");
    properties.setProperty("adb.catalog.table.TEST.id", "1");
    properties.setProperty("adb.catalog.table.TEST.epoch", "0");
    return AdbClusterOrchestrationConfig.fromProperties(properties);
  }

  private void node(Properties properties, String nodeId, int port,
      String role) {
    String prefix = "adb.cluster.node." + nodeId + ".";
    properties.setProperty(prefix + "host", "127.0.0.1");
    properties.setProperty(prefix + "port", String.valueOf(port));
    properties.setProperty(prefix + "dataDir", path(tempDir.resolve(nodeId)));
    properties.setProperty(prefix + "role", role);
  }

  private static String path(Path path) {
    return path.toAbsolutePath().toString().replace('\\', '/');
  }
}
