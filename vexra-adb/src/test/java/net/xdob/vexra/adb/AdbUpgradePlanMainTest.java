package net.xdob.vexra.adb;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

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
