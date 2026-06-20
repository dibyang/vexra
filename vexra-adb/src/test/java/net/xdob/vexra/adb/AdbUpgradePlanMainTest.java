package net.xdob.vexra.adb;

import net.xdob.vexra.adb.db.AdbUnsupportedProductionFeatureException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADB 滚动升级计划命令测试。
 */
class AdbUpgradePlanMainTest {
  /**
   * 验证命令会输出下一个待升级节点和后续步骤。
   */
  @Test
  void shouldRenderNextNodeAndRemainingUpgradeSteps() throws Exception {
    String output = capture(() -> AdbUpgradePlanMain.main(new String[]{
        "--targetVersion", "0.6.0",
        "--nodes", "node-a,node-b,witness-a",
        "--upgraded", "node-a"}));

    assertTrue(output.contains("PASS"));
    assertTrue(output.contains("targetVersion=0.6.0"));
    assertTrue(output.contains("nextNode=node-b"));
    assertTrue(output.contains("1. upgrade node-b to 0.6.0"));
    assertTrue(output.contains("2. upgrade witness-a to 0.6.0"));
    assertTrue(output.contains("rollback command: rollback node-b"));
  }

  /**
   * 验证显式生产参数缺少安全默认值时，滚动升级计划入口拒绝输出 runbook。
   */
  @Test
  void shouldRejectUpgradePlanWhenProductionGuardIsNotReady() {
    SQLException error = assertThrows(SQLException.class,
        () -> AdbUpgradePlanMain.main(new String[]{
            "--targetVersion", "0.6.0",
            "--nodes", "node-a,node-b,witness-a",
            "--adb.production.mode", "mvp-cluster",
            "--adb.production.topology", "2data1witness"}));

    assertEquals(AdbUnsupportedProductionFeatureException.SQL_STATE,
        error.getSQLState());
  }

  /**
   * 验证安全 2 data + witness 生产参数允许生成滚动升级 runbook。
   */
  @Test
  void shouldRenderUpgradePlanWithSecureProductionGuard() throws Exception {
    String output = capture(() -> AdbUpgradePlanMain.main(new String[]{
        "--targetVersion", "0.6.0",
        "--nodes", "node-a,node-b,witness-a",
        "--adb.production.mode", "mvp-cluster",
        "--adb.production.topology", "2data1witness",
        "--adb.security.tls.enabled", "true",
        "--adb.security.auth.enabled", "true",
        "--adb.security.leastPrivilege.enabled", "true"}));

    assertTrue(output.contains("PASS"));
    assertTrue(output.contains("nextNode=node-a"));
  }

  private static String capture(ThrowingRunnable runnable) throws Exception {
    PrintStream oldOut = System.out;
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try {
      System.setOut(new PrintStream(output, true, "UTF-8"));
      runnable.run();
    } finally {
      System.setOut(oldOut);
    }
    return new String(output.toByteArray(), StandardCharsets.UTF_8);
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}
