package net.xdob.vexra.adb.db;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADB 安全安装模板计划测试。
 *
 * <p>测试覆盖 `ADB-Run-11` 的核心验收：默认安全配置要求 TLS、认证和最小权限，
 * 并能生成 SQL/region 服务安装模板。</p>
 */
class AdbSecureInstallPlanTest {

  /**
   * 验证安全配置会拒绝关闭认证的分布式部署。
   */
  @Test
  void shouldRejectDistributedSecurityWithoutAuth() {
    Properties properties = secureProperties();
    properties.setProperty("adb.security.auth.enabled", "false");

    IllegalArgumentException error = assertThrows(
        IllegalArgumentException.class,
        () -> AdbSecureRuntimeConfig.fromProperties(properties));

    assertTrue(error.getMessage().contains("TLS, auth and least privilege"));
  }

  /**
   * 验证安全安装计划会生成 systemd 与 Windows 服务模板。
   */
  @Test
  void shouldGenerateSecureServiceTemplates() {
    AdbSecureInstallPlan plan = new AdbSecureInstallPlan(
        AdbClusterOrchestrationConfig.fromProperties(clusterProperties()),
        AdbSecureRuntimeConfig.fromProperties(secureProperties()));

    String sqlUnit = plan.sqlSystemdUnit();
    assertTrue(sqlUnit.contains("User=vexra"));
    assertTrue(sqlUnit.contains("ADB_SQL_SERVER_OPTS"));
    assertTrue(sqlUnit.contains("-Dvexra.adb.tls.enabled=true"));
    assertTrue(sqlUnit.contains("-Dvexra.adb.auth.enabled=true"));
    assertTrue(sqlUnit.contains("NoNewPrivileges=true"));
    assertEquals(3, plan.regionSystemdUnits().size());
    assertTrue(plan.regionSystemdUnits().get(0).contains(
        "vexra-adb-region-n1"));
    assertTrue(plan.regionSystemdUnits().get(0).contains(
        "ADB_REGION_NODE_OPTS"));
    assertEquals(4, plan.windowsServiceCommands().size());
    assertTrue(plan.windowsServiceCommands().get(0).contains(
        "vexra-adb-sql"));
  }

  private static Properties secureProperties() {
    Properties properties = new Properties();
    properties.setProperty("adb.security.distributed", "true");
    properties.setProperty("adb.security.tls.enabled", "true");
    properties.setProperty("adb.security.auth.enabled", "true");
    properties.setProperty("adb.security.leastPrivilege.enabled", "true");
    properties.setProperty("adb.security.tls.ca", "conf/ca.pem");
    properties.setProperty("adb.security.tls.certDir", "conf/tls");
    properties.setProperty("adb.security.auth.tokenFile",
        "conf/tokens.properties");
    properties.setProperty("adb.security.privilege.dir", "conf/privileges");
    properties.setProperty("adb.security.serviceUser", "vexra");
    return properties;
  }

  private static Properties clusterProperties() {
    Properties properties = new Properties();
    properties.setProperty("adb.cluster.runtimeDir", "runtime");
    properties.setProperty("adb.cluster.group",
        "11111111-1111-1111-1111-111111111111");
    properties.setProperty("adb.cluster.nodes", "n1,n2,n3");
    properties.setProperty("adb.cluster.sql.port", "9123");
    properties.setProperty("adb.cluster.sql.baseDir", "work/sql");
    properties.setProperty("adb.cluster.catalog.path",
        "run/adb-catalog.properties");
    node(properties, "n1", "19001");
    node(properties, "n2", "19002");
    node(properties, "n3", "19003");
    properties.setProperty("adb.catalog.tso.readTs", "20000");
    properties.setProperty("adb.catalog.table.TEST.id", "1");
    properties.setProperty("adb.catalog.table.TEST.epoch", "0");
    return properties;
  }

  private static void node(Properties properties, String nodeId, String port) {
    String prefix = "adb.cluster.node." + nodeId + ".";
    properties.setProperty(prefix + "host", "127.0.0.1");
    properties.setProperty(prefix + "port", port);
    properties.setProperty(prefix + "dataDir", "work/" + nodeId);
    properties.setProperty(prefix + "role", "DATA_NODE");
  }
}
