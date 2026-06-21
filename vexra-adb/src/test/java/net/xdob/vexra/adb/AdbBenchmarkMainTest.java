package net.xdob.vexra.adb;

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
 * ADB benchmark 命令行入口测试。
 *
 * <p>测试覆盖 ldb 文件库默认使用方式、JDBC 批量事务、store 直连基线、properties 报告输出和
 * 非法 workload 校验。测试不使用 mem 模式，避免给生产性能判断引入无意义路径。</p>
 */
class AdbBenchmarkMainTest {
  @TempDir
  Path tempDir;

  /**
   * 验证 ldb JDBC URL 可以执行 mixed benchmark 并写出结果。
   */
  @Test
  void shouldRunMixedBenchmarkAgainstLdbUrl() throws Exception {
    Path output = tempDir.resolve("benchmark.properties");
    String url = "jdbc:adb:ldb:" + tempDir.resolve("db").resolve(
        "adb-benchmark") + ";DB_CLOSE_DELAY=0";

    AdbBenchmarkResult result = AdbBenchmarkMain.run(new String[]{
        "--url", url,
        "--workload", "mixed",
        "--rows", "20",
        "--warmupOperations", "3",
        "--operations", "10",
        "--rangeSize", "4",
        "--output", output.toString()
    });
    Properties properties = load(output);

    assertEquals("jdbc", result.getMode());
    assertEquals("mixed", result.getWorkload());
    assertEquals(10L, result.getOperations());
    assertEquals(0L, result.getFailedOperations());
    assertTrue(result.getThroughputPerSecond() > 0D);
    assertEquals("jdbc", properties.getProperty("mode"));
    assertEquals("true", properties.getProperty("passed"));
    assertEquals("10", properties.getProperty("operations"));
    assertTrue(Long.parseLong(properties.getProperty(
        "sqlDiagnostics.totalSqlCount")) > 0L);
    assertTrue(Integer.parseInt(properties.getProperty(
        "sqlDiagnostics.operationStats.count")) > 0);
    assertFalse(url.startsWith("jdbc:adb:mem:"));
  }

  /**
   * 验证 JDBC benchmark 支持按批次提交事务，用于区分单条 auto-commit 和批量写入成本。
   */
  @Test
  void shouldRunJdbcBenchmarkWithTransactionBatch() throws Exception {
    String url = "jdbc:adb:ldb:" + tempDir.resolve("batch").resolve(
        "adb-benchmark") + ";DB_CLOSE_DELAY=0";

    AdbBenchmarkResult result = AdbBenchmarkMain.run(new String[]{
        "--url", url,
        "--workload", "insert",
        "--rows", "10",
        "--warmupOperations", "0",
        "--operations", "12",
        "--transactionBatchSize", "4",
        "--output", tempDir.resolve("batch.properties").toString()
    });

    assertEquals("jdbc", result.getMode());
    assertEquals("insert", result.getWorkload());
    assertEquals(12L, result.getOperations());
    assertEquals(0L, result.getFailedOperations());
  }

  /**
   * 验证 store 模式可以绕过 SQL / table engine，形成 LdbStore 本地封装基线。
   */
  @Test
  void shouldRunStoreBenchmarkAgainstLdbStore() throws Exception {
    Path output = tempDir.resolve("store.properties");
    Path storeDir = tempDir.resolve("store");

    AdbBenchmarkResult result = AdbBenchmarkMain.run(new String[]{
        "--mode", "store",
        "--storeDir", storeDir.toString(),
        "--workload", "mixed",
        "--rows", "20",
        "--warmupOperations", "3",
        "--operations", "10",
        "--rangeSize", "4",
        "--output", output.toString()
    });
    Properties properties = load(output);

    assertEquals("store", result.getMode());
    assertEquals("mixed", result.getWorkload());
    assertEquals(10L, result.getOperations());
    assertEquals(0L, result.getFailedOperations());
    assertEquals("store", properties.getProperty("mode"));
    assertEquals(storeDir.toString(), properties.getProperty("url"));
    assertEquals(null, properties.getProperty("sqlDiagnostics.totalSqlCount"));
  }

  /**
   * 验证 point lookup workload 可以独立运行。
   */
  @Test
  void shouldRunTxnInsertBenchmarkAgainstLdbStore() throws Exception {
    Path output = tempDir.resolve("txn.properties");
    Path storeDir = tempDir.resolve("txn-store");

    AdbBenchmarkResult result = AdbBenchmarkMain.run(new String[]{
        "--mode", "txn",
        "--storeDir", storeDir.toString(),
        "--workload", "insert",
        "--rows", "20",
        "--warmupOperations", "3",
        "--operations", "10",
        "--transactionBatchSize", "5",
        "--output", output.toString()
    });
    Properties properties = load(output);

    assertEquals("txn", result.getMode());
    assertEquals("insert", result.getWorkload());
    assertEquals(10L, result.getOperations());
    assertEquals(0L, result.getFailedOperations());
    assertEquals("txn", properties.getProperty("mode"));
    assertEquals(storeDir.toString(), properties.getProperty("url"));
    assertEquals(null, properties.getProperty("sqlDiagnostics.totalSqlCount"));
  }

  @Test
  void shouldRunPointLookupBenchmarkAgainstLdbUrl() throws Exception {
    String url = "jdbc:adb:ldb:" + tempDir.resolve("lookup").resolve(
        "adb-benchmark") + ";DB_CLOSE_DELAY=0";

    AdbBenchmarkResult result = AdbBenchmarkMain.run(new String[]{
        "--url", url,
        "--workload", "point_lookup",
        "--rows", "10",
        "--warmupOperations", "0",
        "--operations", "5",
        "--output", tempDir.resolve("lookup.properties").toString()
    });

    assertEquals("point_lookup", result.getWorkload());
    assertEquals(5L, result.getOperations());
    assertEquals(0L, result.getFailedOperations());
  }

  /**
   * 验证不支持的 workload 会被拒绝。
   */
  @Test
  void shouldRejectUnsupportedWorkload() {
    String url = "jdbc:adb:ldb:" + tempDir.resolve("bad").resolve(
        "adb-benchmark") + ";DB_CLOSE_DELAY=0";

    try {
      AdbBenchmarkMain.run(new String[]{
          "--url", url,
          "--workload", "mem_only",
          "--output", tempDir.resolve("bad.properties").toString()
      });
    } catch (Exception e) {
      assertTrue(e.getMessage().contains("Unsupported workload"));
      return;
    }
    throw new AssertionError("Unsupported workload should fail");
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
