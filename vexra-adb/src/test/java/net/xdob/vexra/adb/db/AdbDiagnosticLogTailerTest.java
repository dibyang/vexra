package net.xdob.vexra.adb.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADB 诊断日志尾部采集器测试。
 *
 * <p>日志尾部会进入 doctor 诊断归档，测试覆盖最大行数限制和缺失文件说明，
 * 避免故障现场因为一个日志文件缺失而丢掉整个诊断包。</p>
 */
class AdbDiagnosticLogTailerTest {
  @TempDir
  Path tempDir;

  /**
   * 验证只保留日志末尾指定行数。
   *
   * @throws Exception 写入或读取日志失败时抛出
   */
  @Test
  void shouldTailLastLines() throws Exception {
    Path log = tempDir.resolve("region.log");
    Files.write(log, Arrays.asList("l1", "l2", "l3", "l4"),
        StandardCharsets.UTF_8);

    List<String> lines = new AdbDiagnosticLogTailer().tail(log, 2);

    assertEquals(Arrays.asList("l3", "l4"), lines);
  }

  /**
   * 验证缺失日志文件不会让采集流程失败。
   *
   * @throws Exception 读取失败时抛出
   */
  @Test
  void shouldReportMissingLogFile() throws Exception {
    Path missing = tempDir.resolve("missing.log");

    List<String> lines = new AdbDiagnosticLogTailer().tail(missing, 10);

    assertEquals(1, lines.size());
    assertTrue(lines.get(0).contains("missing log file"));
  }
}
