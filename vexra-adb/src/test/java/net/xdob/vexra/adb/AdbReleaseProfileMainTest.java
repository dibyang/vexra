package net.xdob.vexra.adb;

import net.xdob.vexra.adb.db.AdbReleaseProfileResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADB release profile 命令行入口测试。
 *
 * <p>测试覆盖 Gradle/CI 调用 main class 所依赖的参数解析和 evidence 输出行为。</p>
 */
class AdbReleaseProfileMainTest {
  @TempDir
  Path tempDir;

  /**
   * 验证默认参数可以生成通过的 release profile evidence。
   */
  @Test
  void shouldRunPassingProfileFromArgs() throws Exception {
    Path output = tempDir.resolve("pass");

    AdbReleaseProfileResult result = AdbReleaseProfileMain.run(new String[]{
        "--releaseId", "rel-main-pass",
        "--version", "0.7.0",
        "--output", output.toString(),
        "--commands", "cmd-a;cmd-b",
        "--checksums", "backup=1;restore=1"
    });
    Properties properties = load(result.getEvidenceFile());

    assertTrue(result.isPassed());
    assertEquals("true", properties.getProperty("passed"));
    assertEquals("cmd-a;cmd-b", properties.getProperty("commands"));
    assertEquals("backup=1;restore=1", properties.getProperty("checksums"));
  }

  /**
   * 验证失败的 profile 仍然输出 evidence。
   */
  @Test
  void shouldArchiveEvidenceWhenProfileFromArgsFails() throws Exception {
    Path output = tempDir.resolve("fail");

    AdbReleaseProfileResult result = AdbReleaseProfileMain.run(new String[]{
        "--releaseId", "rel-main-fail",
        "--version", "0.7.0",
        "--output", output.toString(),
        "--sqlRegionSmokeCycles", "0"
    });
    Properties properties = load(result.getEvidenceFile());

    assertFalse(result.isPassed());
    assertTrue(result.getFailureReasons().contains(
        "sql/region smoke cycle is missing"));
    assertEquals("false", properties.getProperty("passed"));
    assertEquals("sql/region smoke cycle is missing",
        properties.getProperty("failureReasons"));
  }

  private static Properties load(Path file) throws Exception {
    assertTrue(Files.exists(file));
    Properties properties = new Properties();
    try (InputStream input = Files.newInputStream(file)) {
      properties.load(input);
    }
    return properties;
  }
}
