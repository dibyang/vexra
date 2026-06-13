package net.xdob.vexra.adb.db;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADB 部署计划测试。
 *
 * <p>测试覆盖 ADB-Prod-05 的部署清单生成和安全配置拒绝逻辑。</p>
 */
class AdbDeploymentPlanTest {

  /**
   * 验证分布式部署计划会生成带安全材料的启动命令。
   */
  @Test
  void shouldGenerateAuditableStartupCommands() {
    AdbDeploymentPlan plan = new AdbDeploymentPlan(
        new AdbDistributedRuntimeOptions(true, true, true),
        "java", "vexra-adb-node.jar", Arrays.asList(
        node("node-a", 17701, "/data/a", AdbDeploymentNodeRole.DATA_NODE),
        node("node-b", 17702, "/data/b", AdbDeploymentNodeRole.DATA_NODE),
        node("witness-a", 17703, "/data/w",
            AdbDeploymentNodeRole.WITNESS_NODE)));

    assertEquals(3, plan.startupCommands().size());
    assertTrue(plan.startupCommands().get(0).contains(
        "-Dvexra.adb.nodeId=node-a"));
    assertTrue(plan.startupCommands().get(0).contains(
        "-Dvexra.adb.tlsCert=/tls/node-a.pem"));
    assertTrue(plan.startupCommands().get(2).contains(
        "-Dvexra.adb.role=WITNESS_NODE"));
  }

  /**
   * 验证启用分布式模式时必须满足安全开关和 2 data + 1 witness 拓扑。
   */
  @Test
  void shouldRejectUnsafeOrInvalidDistributedDeployment() {
    assertThrows(IllegalArgumentException.class,
        () -> new AdbDistributedRuntimeOptions(true, false, true));
    assertThrows(IllegalArgumentException.class,
        () -> new AdbDeploymentPlan(
            new AdbDistributedRuntimeOptions(true, true, true),
            "java", "vexra-adb-node.jar", Arrays.asList(
            node("node-a", 17701, "/data/a", AdbDeploymentNodeRole.DATA_NODE),
            node("node-b", 17702, "/data/b",
                AdbDeploymentNodeRole.DATA_NODE))));
  }

  /**
   * 验证部署计划拒绝重复端点和重复数据目录。
   */
  @Test
  void shouldRejectDuplicateEndpointOrDataDir() {
    assertThrows(IllegalArgumentException.class,
        () -> new AdbDeploymentPlan(
            new AdbDistributedRuntimeOptions(true, true, true),
            "java", "vexra-adb-node.jar", Arrays.asList(
            node("node-a", 17701, "/data/a", AdbDeploymentNodeRole.DATA_NODE),
            node("node-b", 17701, "/data/b", AdbDeploymentNodeRole.DATA_NODE),
            node("witness-a", 17703, "/data/w",
                AdbDeploymentNodeRole.WITNESS_NODE))));

    assertThrows(IllegalArgumentException.class,
        () -> new AdbDeploymentPlan(
            new AdbDistributedRuntimeOptions(true, true, true),
            "java", "vexra-adb-node.jar", Arrays.asList(
            node("node-a", 17701, "/data/a", AdbDeploymentNodeRole.DATA_NODE),
            node("node-b", 17702, "/data/a", AdbDeploymentNodeRole.DATA_NODE),
            node("witness-a", 17703, "/data/w",
                AdbDeploymentNodeRole.WITNESS_NODE))));
  }

  private static AdbDeploymentNodeSpec node(String nodeId, int port,
      String dataDir, AdbDeploymentNodeRole role) {
    return new AdbDeploymentNodeSpec(nodeId, "127.0.0.1", port, dataDir,
        role, "/tls/" + nodeId + ".pem", "/priv/" + nodeId + ".json");
  }
}
