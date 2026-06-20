package net.xdob.vexra.adb.db;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADB-GA-01 生产范围 guard 测试。
 *
 * <p>测试覆盖生产 MVP 范围冻结：单机模式保持兼容，2 data + 1 witness
 * 是生产集群主路径，纯 2 data、缺安全默认值和跨 region 事务默认被拒绝。</p>
 */
class AdbProductionGuardTest {

  /**
   * 验证默认 guard 保持单机 SQL 可用，同时拒绝分布式能力。
   */
  @Test
  void shouldAllowLocalSqlAndRejectDistributedCapabilityInSingleMode()
      throws Exception {
    AdbProductionGuard guard = AdbProductionGuard.singleNodeDefault();

    assertEquals(AdbProductionState.SINGLE_READY, guard.getState());
    guard.requireCapability(AdbProductionCapability.LOCAL_SQL,
        AdbProductionRequestContext.local("local-select"));

    AdbUnsupportedProductionFeatureException error = assertThrows(
        AdbUnsupportedProductionFeatureException.class,
        () -> guard.requireCapability(AdbProductionCapability.DISTRIBUTED_SQL,
            AdbProductionRequestContext.local("distributed-select")));
    assertEquals(AdbUnsupportedProductionFeatureException.SQL_STATE,
        error.getSQLState());
    assertTrue(error.getMessage().contains("cluster mode is not ready"));
  }

  /**
   * 验证生产集群模式必须使用 2 data + 1 witness 和安全默认值。
   */
  @Test
  void shouldAllowMvpClusterCapabilitiesForSecureWitnessTopology()
      throws Exception {
    AdbProductionGuard guard = mvpClusterGuard();

    assertEquals(AdbProductionState.CLUSTER_READY, guard.getState());
    guard.requireReady();
    guard.requireCapability(AdbProductionCapability.DISTRIBUTED_SQL,
        AdbProductionRequestContext.local("remote-scan"));
    guard.requireCapability(AdbProductionCapability.SINGLE_REGION_TRANSACTION,
        new AdbProductionRequestContext("commit", 7,
            Collections.singletonList("r1")));
    guard.validateTransactionRegions(Collections.singletonList("r1"),
        new AdbProductionRequestContext("commit", 7,
            Collections.singletonList("r1")));
    guard.validateClusterTopology(validDeploymentPlan());
  }

  /**
   * 验证缺少认证、TLS 或最小权限时生产集群会进入拒绝状态。
   */
  @Test
  void shouldRejectMvpClusterWhenSecurityDefaultsAreMissing() {
    Properties properties = secureClusterProperties();
    properties.setProperty(AdbProductionGuard.AUTH_KEY, "false");

    AdbProductionGuard guard = AdbProductionGuard.fromProperties(properties);

    assertEquals(AdbProductionState.REJECTED, guard.getState());
    SQLException error = assertThrows(SQLException.class, guard::requireReady);
    assertTrue(error.getMessage().contains("TLS, auth and least privilege"));
  }

  /**
   * 验证纯两个数据节点自动写入拓扑在生产范围内被拒绝。
   */
  @Test
  void shouldRejectPureTwoDataTopology() {
    Properties properties = secureClusterProperties();
    properties.setProperty(AdbProductionGuard.TOPOLOGY_KEY, "pure-2data");

    AdbProductionGuard guard = AdbProductionGuard.fromProperties(properties);

    assertEquals(AdbProductionState.REJECTED, guard.getState());
    assertTrue(guard.getRejectionReason().contains("pure 2-data"));
  }

  /**
   * 验证跨 region 事务默认不是生产 MVP 能力。
   */
  @Test
  void shouldRejectCrossRegionTransactionByDefault() {
    AdbProductionGuard guard = mvpClusterGuard();

    AdbUnsupportedProductionFeatureException error = assertThrows(
        AdbUnsupportedProductionFeatureException.class,
        () -> guard.validateTransactionRegions(Arrays.asList("r1", "r2"),
            new AdbProductionRequestContext("commit", 9,
                Arrays.asList("r1", "r2"))));

    assertTrue(error.getMessage().contains("CROSS_REGION_TRANSACTION"));
    assertTrue(error.getMessage().contains("experimental capability is disabled"));
  }

  /**
   * 验证实验模式只有在显式 opt-in 后才放行实验能力。
   */
  @Test
  void shouldAllowExperimentalCapabilityOnlyWithExplicitOptIn()
      throws Exception {
    Properties properties = secureClusterProperties();
    properties.setProperty(AdbProductionGuard.MODE_KEY, "experimental");
    properties.setProperty(AdbProductionGuard.ALLOW_EXPERIMENTAL_KEY, "true");
    AdbProductionGuard guard = AdbProductionGuard.fromProperties(properties);

    guard.requireCapability(AdbProductionCapability.CROSS_REGION_TRANSACTION,
        new AdbProductionRequestContext("commit", 10,
            Arrays.asList("r1", "r2")));
    guard.validateTransactionRegions(Arrays.asList("r1", "r2"),
        new AdbProductionRequestContext("commit", 10,
            Arrays.asList("r1", "r2")));

    properties.setProperty(AdbProductionGuard.ALLOW_EXPERIMENTAL_KEY, "false");
    AdbProductionGuard rejected = AdbProductionGuard.fromProperties(properties);
    assertThrows(AdbUnsupportedProductionFeatureException.class,
        () -> rejected.requireCapability(
            AdbProductionCapability.CROSS_REGION_TRANSACTION,
            AdbProductionRequestContext.local("commit")));
  }

  private static AdbProductionGuard mvpClusterGuard() {
    return AdbProductionGuard.fromProperties(secureClusterProperties());
  }

  private static Properties secureClusterProperties() {
    Properties properties = new Properties();
    properties.setProperty(AdbProductionGuard.MODE_KEY, "mvp-cluster");
    properties.setProperty(AdbProductionGuard.TOPOLOGY_KEY, "2data1witness");
    properties.setProperty(AdbProductionGuard.TLS_KEY, "true");
    properties.setProperty(AdbProductionGuard.AUTH_KEY, "true");
    properties.setProperty(AdbProductionGuard.LEAST_PRIVILEGE_KEY, "true");
    return properties;
  }

  private static AdbDeploymentPlan validDeploymentPlan() {
    return new AdbDeploymentPlan(
        new AdbDistributedRuntimeOptions(true, true, true),
        "java", "vexra-adb-node.jar", Arrays.asList(
        node("node-a", 17701, "/data/a", AdbDeploymentNodeRole.DATA_NODE),
        node("node-b", 17702, "/data/b", AdbDeploymentNodeRole.DATA_NODE),
        node("witness-a", 17703, "/data/w",
            AdbDeploymentNodeRole.WITNESS_NODE)));
  }

  private static AdbDeploymentNodeSpec node(String nodeId, int port,
      String dataDir, AdbDeploymentNodeRole role) {
    return new AdbDeploymentNodeSpec(nodeId, "127.0.0.1", port, dataDir,
        role, "/tls/" + nodeId + ".pem", "/priv/" + nodeId + ".json");
  }
}
