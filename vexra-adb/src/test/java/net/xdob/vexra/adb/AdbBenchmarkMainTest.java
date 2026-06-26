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
   * 验证 benchmark 可以用同一套 SQL workload 跑 h2db 默认表，作为 ADB 对比基线。
   */
  @Test
  void shouldRunPointLookupBenchmarkAgainstH2TableEngine() throws Exception {
    Path output = tempDir.resolve("h2-table-engine.properties");
    String url = "jdbc:h2:file:" + tempDir.resolve("h2-baseline").resolve(
        "adb-benchmark") + ";DB_CLOSE_DELAY=0";

    AdbBenchmarkResult result = AdbBenchmarkMain.run(new String[]{
        "--url", url,
        "--tableEngine", "h2",
        "--workload", "point_lookup",
        "--rows", "10",
        "--warmupOperations", "0",
        "--operations", "5",
        "--output", output.toString()
    });
    Properties properties = load(output);

    assertEquals("jdbc", result.getMode());
    assertEquals("point_lookup", result.getWorkload());
    assertEquals(5L, result.getOperations());
    assertEquals(0L, result.getFailedOperations());
    assertEquals("h2", properties.getProperty("tableEngine"));
    assertEquals("0", properties.getProperty("sqlDiagnostics.totalSqlCount"));
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
   * 验证 raw bulk 诊断模式可以在完整 JDBC 连接和 H2 commit 边界下运行。
   *
   * <p>该模式用于模拟 h2db 未来提供批量 DML 参数 hook 后的 ADB 上限，不对外承诺为业务 API。
   * 测试只校验接线路径，不把本地机器性能阈值固化到单元测试。</p>
   */
  @Test
  void shouldRunJdbcRawBulkBenchmarkForHookSimulation() throws Exception {
    Path output = tempDir.resolve("raw-bulk.properties");
    String url = "jdbc:adb:ldb:" + tempDir.resolve("raw-bulk").resolve(
        "adb-benchmark") + ";DB_CLOSE_DELAY=0";

    AdbBenchmarkResult result = AdbBenchmarkMain.run(new String[]{
        "--mode", "jdbc_raw_bulk",
        "--url", url,
        "--workload", "insert",
        "--rows", "10",
        "--warmupOperations", "0",
        "--operations", "8",
        "--transactionBatchSize", "4",
        "--statementBatchSize", "4",
        "--sqlDiagnostics", "false",
        "--output", output.toString()
    });
    Properties properties = load(output);

    assertEquals("jdbc_raw_bulk", result.getMode());
    assertEquals("insert", result.getWorkload());
    assertEquals(8L, result.getOperations());
    assertEquals(0L, result.getFailedOperations());
    assertEquals("adb", properties.getProperty("tableEngine"));
  }

  /**
   * 验证 benchmark 可以打开二级索引写入场景，便于跟踪 bulk insert 的索引路径成本。
   */
  @Test
  void shouldRunInsertBenchmarkWithSecondaryIndex() throws Exception {
    Path output = tempDir.resolve("secondary-index.properties");
    String url = "jdbc:adb:ldb:" + tempDir.resolve("secondary-index").resolve(
        "adb-benchmark") + ";DB_CLOSE_DELAY=0";

    AdbBenchmarkResult result = AdbBenchmarkMain.run(new String[]{
        "--url", url,
        "--workload", "insert",
        "--rows", "10",
        "--warmupOperations", "0",
        "--operations", "8",
        "--transactionBatchSize", "4",
        "--statementBatchSize", "4",
        "--secondaryIndex", "true",
        "--output", output.toString()
    });
    Properties properties = load(output);

    assertEquals("jdbc", result.getMode());
    assertEquals("insert", result.getWorkload());
    assertEquals(8L, result.getOperations());
    assertEquals(0L, result.getFailedOperations());
    assertEquals("true", properties.getProperty("secondaryIndex"));
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
        "--operations", "10",
        "--rangeSize", "4",
        "--transactionBatchSize", "4",
        "--threads", "2",
        "--output", output.toString()
    });
    Properties properties = load(output);

    assertEquals("jdbc", result.getMode());
    assertEquals("mixed", result.getWorkload());
    assertEquals(10L, result.getOperations());
    assertEquals(0L, result.getFailedOperations());
    assertEquals("2", result.getDetails().get("concurrency.threads"));
    assertEquals("10", result.getDetails().get(
        "concurrency.completedOperations"));
    assertEquals("2", properties.getProperty("concurrency.threads"));
    assertEquals("10", properties.getProperty(
        "concurrency.completedOperations"));
    assertEquals("operationsOnly", properties.getProperty(
        "concurrency.measuredWindow"));
    assertEquals("1", properties.getProperty("mixedLatency.write.count"));
    assertEquals("7", properties.getProperty(
        "mixedLatency.pointLookup.count"));
    assertEquals("2", properties.getProperty(
        "mixedLatency.rangeCount.count"));
    assertEquals("2", properties.getProperty("mixedLatency.commit.count"));
    assertEquals("1", properties.getProperty("mixedAllocation.write.count"));
    assertEquals("7", properties.getProperty(
        "mixedAllocation.pointLookup.count"));
    assertEquals("2", properties.getProperty(
        "mixedAllocation.rangeCount.count"));
    assertEquals("2", properties.getProperty(
        "mixedAllocation.commit.count"));
    assertTrue(Long.parseLong(properties.getProperty(
        "mixedLatency.write.maxLatencyMicros")) >= 0L);
    assertTrue(Long.parseLong(properties.getProperty(
        "mixedLatency.pointLookup.maxLatencyMicros")) >= 0L);
    assertTrue(Long.parseLong(properties.getProperty(
        "mixedLatency.rangeCount.maxLatencyMicros")) >= 0L);
    assertTrue(Long.parseLong(properties.getProperty(
        "mixedAllocation.write.totalBytes")) >= 0L);
    assertTrue(Long.parseLong(properties.getProperty(
        "mixedAllocation.pointLookup.totalBytes")) >= 0L);
    assertTrue(Long.parseLong(properties.getProperty(
        "mixedAllocation.rangeCount.totalBytes")) >= 0L);
    assertTrue(Long.parseLong(properties.getProperty(
        "mixedAllocation.commit.totalBytes")) >= 0L);
    assertTrue(Long.parseLong(properties.getProperty(
        "mixedAllocation.jdbc.parameterSet.count")) > 0L);
    assertTrue(Long.parseLong(properties.getProperty(
        "mixedAllocation.jdbc.statementExecute.count")) > 0L);
    assertTrue(Long.parseLong(properties.getProperty(
        "mixedAllocation.jdbc.resultNext.count")) > 0L);
    assertTrue(Long.parseLong(properties.getProperty(
        "mixedAllocation.jdbc.resultRead.count")) > 0L);
    assertTrue(Long.parseLong(properties.getProperty(
        "mixedAllocation.jdbc.resultClose.count")) > 0L);
    assertTrue(Long.parseLong(properties.getProperty(
        "mixedAllocation.plan.pointLookup.visibleColumn.count")) > 0L);
    assertTrue(Long.parseLong(properties.getProperty(
        "mixedAllocation.plan.rangeCount.countVisible.count")) > 0L);
    assertTrue(Integer.parseInt(properties.getProperty(
        "sqlDiagnostics.phaseStats.count")) > 0);
    assertFalse(containsPropertyValue(properties, "ADB_ROW_COUNT_PREWARM"));
    assertAllocationDetails(properties);
  }

  /**
   * 验证 store 模式可以绕过 SQL / table engine，形成 LdbStore 本地封装基线。
   */
  @Test
  void shouldRunMixedBenchmarkWithPhysicalRangeCountFastPath()
      throws Exception {
    String property = "vexra.adb.rangeCount.physicalFastPath.enabled";
    String previous = System.getProperty(property);
    System.setProperty(property, "true");
    try {
      Path output = tempDir.resolve("physical-range-count.properties");
      String url = "jdbc:adb:ldb:" + tempDir.resolve(
          "physical-range-count").resolve("adb-benchmark")
          + ";DB_CLOSE_DELAY=0";

      AdbBenchmarkResult result = AdbBenchmarkMain.run(new String[]{
          "--url", url,
          "--workload", "mixed",
          "--rows", "20",
          "--warmupOperations", "4",
          "--operations", "10",
          "--rangeSize", "4",
          "--transactionBatchSize", "4",
          "--threads", "2",
          "--output", output.toString()
      });
      Properties properties = load(output);

      assertEquals("mixed", result.getWorkload());
      assertEquals(0L, result.getFailedOperations());
      assertTrue(containsPropertyValue(properties,
          "ADB_RANGE_COUNT_PHYSICAL_COUNT"));
      assertAllocationDetails(properties);
    } finally {
      if (previous == null) {
        System.clearProperty(property);
      } else {
        System.setProperty(property, previous);
      }
    }
  }

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
   * 验证 store 模式可以执行 allocation 分界 workload，便于拆分 LDB cursor、visitor/view
   * 和 ADB row materialization 的分配来源。
   */
  @Test
  void shouldRunStoreAllocationBoundaryBenchmarks() throws Exception {
    String[] workloads = new String[]{
        "alloc_count_closed",
        "alloc_scan_empty",
        "alloc_scan_view",
        "alloc_scan_materialize"
    };
    for (String workload : workloads) {
      Path output = tempDir.resolve(workload + ".properties");
      Path storeDir = tempDir.resolve(workload + "-store");

      AdbBenchmarkResult result = AdbBenchmarkMain.run(new String[]{
          "--mode", "store",
          "--storeDir", storeDir.toString(),
          "--workload", workload,
          "--rows", "20",
          "--warmupOperations", "2",
          "--operations", "4",
          "--rangeSize", "4",
          "--output", output.toString()
      });
      Properties properties = load(output);

      assertEquals("store", result.getMode());
      assertEquals(workload, result.getWorkload());
      assertEquals(4L, result.getOperations());
      assertEquals(0L, result.getFailedOperations());
      assertEquals(workload, properties.getProperty("workload"));
      assertAllocationDetails(properties);
    }
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

  @Test
  void shouldRunLocalWriteRangeCountBenchmarkAgainstLdbUrl() throws Exception {
    Path output = tempDir.resolve("local-range-count.properties");
    String url = "jdbc:adb:ldb:" + tempDir.resolve("local-range-count")
        .resolve("adb-benchmark") + ";DB_CLOSE_DELAY=0";

    AdbBenchmarkResult result = AdbBenchmarkMain.run(new String[]{
        "--url", url,
        "--workload", "range_count_local_write",
        "--rows", "10",
        "--warmupOperations", "0",
        "--operations", "5",
        "--rangeSize", "4",
        "--transactionBatchSize", "5",
        "--output", output.toString()
    });
    Properties properties = load(output);

    assertEquals("range_count_local_write", result.getWorkload());
    assertEquals(5L, result.getOperations());
    assertEquals(0L, result.getFailedOperations());
    assertTrue(containsPropertyValue(properties,
        "ADB_RANGE_COUNT_VISIBLE_COUNT_RAW_LOCAL"));
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
