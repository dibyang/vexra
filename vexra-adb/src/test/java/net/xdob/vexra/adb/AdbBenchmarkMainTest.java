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
    assertAllocationDetails(properties);
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
   * 验证 JDBC benchmark 可以用多个 ldb 连接并发执行，并输出线程诊断。
   */
  @Test
  void shouldRunConcurrentMixedBenchmarkAgainstLdbUrl() throws Exception {
    Path output = tempDir.resolve("concurrent.properties");
    String url = "jdbc:adb:ldb:" + tempDir.resolve("concurrent").resolve(
        "adb-benchmark") + ";DB_CLOSE_DELAY=0";

    AdbBenchmarkResult result = AdbBenchmarkMain.run(new String[]{
        "--url", url,
        "--workload", "mixed",
        "--rows", "20",
        "--warmupOperations", "4",
        "--operations", "8",
        "--rangeSize", "4",
        "--transactionBatchSize", "4",
        "--threads", "2",
        "--output", output.toString()
    });
    Properties properties = load(output);

    assertEquals("jdbc", result.getMode());
    assertEquals("mixed", result.getWorkload());
    assertEquals(8L, result.getOperations());
    assertEquals(0L, result.getFailedOperations());
    assertEquals("2", result.getDetails().get("concurrency.threads"));
    assertEquals("8", result.getDetails().get(
        "concurrency.completedOperations"));
    assertEquals("2", properties.getProperty("concurrency.threads"));
    assertEquals("8", properties.getProperty(
        "concurrency.completedOperations"));
    assertTrue(Integer.parseInt(properties.getProperty(
        "sqlDiagnostics.phaseStats.count")) > 0);
    assertAllocationDetails(properties);
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
    assertAllocationDetails(properties);
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
  void shouldRunJdbcBulkInsertBenchmarkAgainstLdbUrl() throws Exception {
    Path output = tempDir.resolve("jdbc-bulk.properties");
    String url = "jdbc:adb:ldb:" + tempDir.resolve("jdbc-bulk").resolve(
        "adb-benchmark") + ";DB_CLOSE_DELAY=0";

    AdbBenchmarkResult result = AdbBenchmarkMain.run(new String[]{
        "--mode", "jdbc_bulk",
        "--url", url,
        "--workload", "insert",
        "--rows", "20",
        "--warmupOperations", "3",
        "--operations", "10",
        "--transactionBatchSize", "5",
        "--statementBatchSize", "5",
        "--output", output.toString()
    });
    Properties properties = load(output);

    assertEquals("jdbc_bulk", result.getMode());
    assertEquals("insert", result.getWorkload());
    assertEquals(10L, result.getOperations());
    assertEquals(0L, result.getFailedOperations());
    assertEquals("jdbc_bulk", properties.getProperty("mode"));
    assertTrue(Long.parseLong(properties.getProperty(
        "sqlDiagnostics.totalSqlCount")) > 0L);
    assertAllocationDetails(properties);
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

  @Test
  void shouldRunPointLookupAllBenchmarkAgainstLdbUrl() throws Exception {
    String url = "jdbc:adb:ldb:" + tempDir.resolve("lookup-all").resolve(
        "adb-benchmark") + ";DB_CLOSE_DELAY=0";

    AdbBenchmarkResult result = AdbBenchmarkMain.run(new String[]{
        "--url", url,
        "--workload", "point_lookup_all",
        "--rows", "10",
        "--warmupOperations", "0",
        "--operations", "5",
        "--output", tempDir.resolve("lookup-all.properties").toString()
    });

    assertEquals("point_lookup_all", result.getWorkload());
    assertEquals(5L, result.getOperations());
    assertEquals(0L, result.getFailedOperations());
  }

  @Test
  void shouldRunPrimaryFindBenchmarkAgainstLdbUrl() throws Exception {
    Path output = tempDir.resolve("primary-find.properties");
    String url = "jdbc:adb:ldb:" + tempDir.resolve("primary-find").resolve(
        "adb-benchmark") + ";DB_CLOSE_DELAY=0";

    AdbBenchmarkResult result = AdbBenchmarkMain.run(new String[]{
        "--url", url,
        "--workload", "primary_find",
        "--rows", "10",
        "--warmupOperations", "0",
        "--operations", "5",
        "--output", output.toString()
    });
    Properties properties = load(output);

    assertEquals("primary_find", result.getWorkload());
    assertEquals(5L, result.getOperations());
    assertEquals(0L, result.getFailedOperations());
    assertTrue(containsPropertyValue(properties,
        "ADB_TABLE_PRIMARY_FIND ADB_BENCH"));
  }

  @Test
  void shouldRunTableCountBenchmarkAgainstLdbUrl() throws Exception {
    String url = "jdbc:adb:ldb:" + tempDir.resolve("table-count").resolve(
        "adb-benchmark") + ";DB_CLOSE_DELAY=0";

    AdbBenchmarkResult result = AdbBenchmarkMain.run(new String[]{
        "--url", url,
        "--workload", "table_count",
        "--rows", "10",
        "--warmupOperations", "0",
        "--operations", "5",
        "--output", tempDir.resolve("table-count.properties").toString()
    });

    assertEquals("table_count", result.getWorkload());
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

  private static boolean containsPropertyValue(Properties properties,
      String expectedValue) {
    return properties.values().contains(expectedValue);
  }

  private static void assertAllocationDetails(Properties properties) {
    String supported = properties.getProperty("allocation.supported");
    assertTrue("true".equals(supported) || "false".equals(supported));
    if ("true".equals(supported)) {
      assertTrue(Long.parseLong(properties.getProperty(
          "allocation.totalBytes")) >= 0L);
      assertTrue(Long.parseLong(properties.getProperty(
          "allocation.bytesPerOperation")) >= 0L);
    }
  }
}
