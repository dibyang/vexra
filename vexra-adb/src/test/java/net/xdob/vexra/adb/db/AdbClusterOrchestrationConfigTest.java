package net.xdob.vexra.adb.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADB 集群自动编排配置测试。
 *
 * <p>测试覆盖 `ADB-Run-10` 的核心验收：一份集群配置可以生成 SQL server 命令、
 * region node 命令、共享 catalog 内容和预检诊断。</p>
 */
class AdbClusterOrchestrationConfigTest {
  @TempDir
  Path tempDir;

  /**
   * 验证一份集群配置可以生成完整运行计划。
   */
  @Test
  void shouldBuildClusterPlanFromSingleConfig() throws Exception {
    Path config = writeConfig("19001", "19002", "19003");

    AdbClusterOrchestrationPlan plan =
        AdbClusterOrchestrationConfig.load(config).toPlan();
    Properties catalog = plan.catalogProperties();

    assertTrue(plan.sqlServerCommand().contains("adb-sql-server.bat"));
    assertTrue(plan.sqlServerCommand().contains("--port 9123"));
    assertEquals(3, plan.regionNodeCommands().size());
    assertTrue(plan.regionNodeCommands().get(0).contains(
        "adb-region-node.bat"));
    assertTrue(plan.regionNodeCommands().get(0).contains("--node n1"));
    assertTrue(plan.regionNodeCommands().get(0).contains(
        "--peers n1@127.0.0.1:19001,n2@127.0.0.1:19002,"
            + "n3@127.0.0.1:19003"));
    assertEquals("11111111-1111-1111-1111-111111111111",
        catalog.getProperty("adb.catalog.raft.group"));
    assertEquals("20000", catalog.getProperty("adb.catalog.tso.readTs"));
    assertEquals("1", catalog.getProperty("adb.catalog.table.TEST.id"));
    assertTrue(plan.preflightChecks().contains("regionNodes=3"));

    Path catalogPath = plan.writeCatalog();
    assertTrue(Files.exists(catalogPath));
  }

  /**
   * 验证编排预检会拒绝重复 region endpoint。
   */
  @Test
  void shouldRejectDuplicateRegionEndpoint() throws Exception {
    Path config = writeConfig("19001", "19001", "19003");

    IllegalArgumentException error = assertThrows(
        IllegalArgumentException.class,
        () -> AdbClusterOrchestrationConfig.load(config));

    assertTrue(error.getMessage().contains("duplicate endpoint"));
  }

  /**
   * 验证生产预检要求 2 data + 1 witness 与安全开关。
   */
  @Test
  void shouldPassProductionPreflightForTwoDataOneWitness() throws Exception {
    Path config = writeProductionConfig(true, true);
    Properties properties = load(config);
    AdbClusterPreflightReport report = new AdbClusterPreflightChecker(
        AdbClusterOrchestrationConfig.fromProperties(properties), properties,
        false, false).check();

    assertTrue(report.isPassed(), report.render());
    assertTrue(report.render().contains("topology=2data1witness"));
  }

  /**
   * 验证生产预检会拒绝未开启 TLS/auth 的配置。
   */
  @Test
  void shouldRejectPreflightWithoutSecurityDefaults() throws Exception {
    Path config = writeProductionConfig(false, false);
    Properties properties = load(config);
    AdbClusterPreflightReport report = new AdbClusterPreflightChecker(
        AdbClusterOrchestrationConfig.fromProperties(properties), properties,
        false, false).check();

    assertTrue(report.render().contains("FAIL tls.enabled=true"));
    assertTrue(report.render().contains("FAIL auth.enabled=true"));
  }

  private Path writeConfig(String n1Port, String n2Port, String n3Port)
      throws Exception {
    Path config = tempDir.resolve("cluster.properties");
    Path runtime = tempDir.resolve("runtime");
    Path catalog = tempDir.resolve("run").resolve("adb-catalog.properties");
    Files.write(config, Arrays.asList(
        "adb.cluster.runtimeDir=" + path(runtime),
        "adb.cluster.group=11111111-1111-1111-1111-111111111111",
        "adb.cluster.nodes=n1,n2,n3",
        "adb.cluster.sql.port=9123",
        "adb.cluster.sql.baseDir=" + path(tempDir.resolve("sql")),
        "adb.cluster.sql.ready=" + path(tempDir.resolve("run/sql.ready")),
        "adb.cluster.sql.stop=" + path(tempDir.resolve("run/sql.stop")),
        "adb.cluster.catalog.path=" + path(catalog),
        "adb.cluster.node.n1.host=127.0.0.1",
        "adb.cluster.node.n1.port=" + n1Port,
        "adb.cluster.node.n1.dataDir=" + path(tempDir.resolve("n1")),
        "adb.cluster.node.n1.role=DATA_NODE",
        "adb.cluster.node.n2.host=127.0.0.1",
        "adb.cluster.node.n2.port=" + n2Port,
        "adb.cluster.node.n2.dataDir=" + path(tempDir.resolve("n2")),
        "adb.cluster.node.n2.role=DATA_NODE",
        "adb.cluster.node.n3.host=127.0.0.1",
        "adb.cluster.node.n3.port=" + n3Port,
        "adb.cluster.node.n3.dataDir=" + path(tempDir.resolve("n3")),
        "adb.cluster.node.n3.role=DATA_NODE",
        "adb.catalog.tso.readTs=20000",
        "adb.catalog.table.TEST.id=1",
        "adb.catalog.table.TEST.epoch=0"), StandardCharsets.UTF_8);
    return config;
  }

  private Path writeProductionConfig(boolean tls, boolean auth)
      throws Exception {
    Path config = writeConfig("19001", "19002", "19003");
    Files.write(config, Arrays.asList(
        "adb.security.tls.enabled=" + tls,
        "adb.security.auth.enabled=" + auth,
        "adb.cluster.node.n3.role=WITNESS_NODE"),
        StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
    return config;
  }

  private static Properties load(Path path) throws Exception {
    Properties properties = new Properties();
    try (java.io.Reader reader = Files.newBufferedReader(path,
        StandardCharsets.UTF_8)) {
      properties.load(reader);
    }
    return properties;
  }

  private static String path(Path path) {
    return path.toAbsolutePath().toString().replace('\\', '/');
  }
}
