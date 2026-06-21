package net.xdob.vexra.adb;

import net.xdob.vexra.adb.db.ScanDirection;
import net.xdob.vexra.adb.db.VersionScanSource;
import net.xdob.vexra.adb.db.AdbSqlDiagnosticSnapshot;
import net.xdob.vexra.adb.db.AdbSqlDiagnosticsRegistry;
import net.xdob.vexra.adb.db.AdbSqlOperationStats;
import net.xdob.vexra.adb.ldb.LdbStore;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * ADB benchmark 命令行入口。
 *
 * <p>该入口用于生成最小可复现性能基线，默认使用文件型 `jdbc:adb:ldb:`，不跑内存模式。
 * 调用方也可以传入 `jdbc:adb:tcp://...` 来测 SQL Server 或分布式路径。`store` 模式会绕过
 * H2 SQL 和 ADB table engine，通过 `LdbStore` 直接测本地 store 封装。当前实现是单线程顺序
 * benchmark，目标是建立稳定口径，而不是替代后续专业压测平台。</p>
 */
public final class AdbBenchmarkMain {
  /** ADB benchmark main class 名称，供 Gradle task 和启动脚本使用。 */
  public static final String MAIN_CLASS = AdbBenchmarkMain.class.getName();
  private static final String DEFAULT_URL =
      "jdbc:adb:ldb:work/adb-benchmark/adb-benchmark;DB_CLOSE_DELAY=0";
  private static final String DEFAULT_STORE_DIR =
      "work/adb-benchmark/store";
  private static final String TABLE_NAME = "ADB_BENCH";

  private AdbBenchmarkMain() {
  }

  /**
   * 执行 benchmark 命令。
   *
   * @param args `--key value` 形式参数
   */
  public static void main(String[] args) {
    try {
      AdbBenchmarkResult result = run(args);
      System.out.println("benchmark.passed="
          + (result.getFailedOperations() == 0));
      System.out.println("benchmark.mode=" + result.getMode());
      System.out.println("benchmark.workload=" + result.getWorkload());
      System.out.println("benchmark.operations=" + result.getOperations());
      System.out.println("benchmark.throughputPerSecond="
          + result.getThroughputPerSecond());
      System.out.println("benchmark.p99LatencyMicros="
          + result.getP99LatencyMicros());
      if (result.getFailedOperations() > 0) {
        System.exit(1);
      }
    } catch (Throwable t) {
      t.printStackTrace(System.err);
      System.err.flush();
      System.exit(1);
    }
  }

  /**
   * 执行 benchmark 并返回结果。
   *
   * @param args 命令行参数
   * @return benchmark 结果
   * @throws Exception 参数解析、JDBC 执行或结果写入失败时抛出
   */
  public static AdbBenchmarkResult run(String[] args) throws Exception {
    Map<String, String> values = parseArgs(args);
    String mode = value(values, "mode", "jdbc").toLowerCase();
    String url = value(values, "url", DEFAULT_URL);
    String storeDir = value(values, "storeDir", DEFAULT_STORE_DIR);
    String workload = value(values, "workload", "mixed").toLowerCase();
    int rows = positiveInt(values, "rows", 1_000);
    int warmupOperations = nonNegativeInt(values, "warmupOperations", 100);
    int operations = positiveInt(values, "operations", 1_000);
    int rangeSize = positiveInt(values, "rangeSize", 32);
    int transactionBatchSize = positiveInt(values, "transactionBatchSize", 1);
    boolean dropTable = bool(values, "dropTable", true);
    Path output = Paths.get(value(values, "output",
        "build/adb-benchmark/adb-benchmark.properties"));

    AdbBenchmarkMain benchmark = new AdbBenchmarkMain();
    AdbBenchmarkResult result;
    if ("jdbc".equals(mode)) {
      result = benchmark.executeJdbc(url, workload, rows, warmupOperations,
          operations, rangeSize, dropTable, transactionBatchSize);
    } else if ("store".equals(mode)) {
      result = benchmark.executeStore(storeDir, workload, rows,
          warmupOperations, operations, rangeSize);
    } else {
      throw new IllegalArgumentException("Unsupported mode: " + mode);
    }
    write(output, result);
    return result;
  }

  /**
   * 执行一次 JDBC benchmark。
   *
   * @param url JDBC URL，默认和推荐为 `jdbc:adb:ldb:*`
   * @param workload workload 名称：`insert`、`point_lookup`、`range_scan` 或 `mixed`
   * @param rows 读类 workload 的预置行数
   * @param warmupOperations 预热操作数
   * @param operations 正式统计操作数
   * @param rangeSize range scan 每次扫描范围
   * @param dropTable 执行前是否删除旧表
   * @param transactionBatchSize JDBC 事务批量提交大小，1 表示自动提交
   * @return benchmark 结果
   * @throws Exception JDBC 初始化或执行失败时抛出
   */
  public AdbBenchmarkResult executeJdbc(String url, String workload, int rows,
      int warmupOperations, int operations, int rangeSize,
      boolean dropTable, int transactionBatchSize) throws Exception {
    requireSupportedWorkload(workload);
    Class.forName("org.h2.Driver");
    try (Connection connection = DriverManager.getConnection(url,
        new Properties())) {
      connection.setAutoCommit(transactionBatchSize <= 1);
      prepareSchema(connection, rows, dropTable);
      commitRemaining(connection, transactionBatchSize, 1);
      try (BenchmarkStatements statements = new BenchmarkStatements(
          connection)) {
        for (int i = 0; i < warmupOperations; i++) {
          executeOperation(statements, workload, rows, rangeSize, i, false);
          commitIfNeeded(connection, transactionBatchSize, i + 1);
        }
        commitRemaining(connection, transactionBatchSize, warmupOperations);
        AdbSqlDiagnosticsRegistry.resetAll();
        long[] latencies = new long[operations];
        long failed = 0;
        int pendingBatchOperations = 0;
        long started = System.nanoTime();
        for (int i = 0; i < operations; i++) {
          long opStarted = System.nanoTime();
          try {
            executeOperation(statements, workload, rows, rangeSize, i, true);
            pendingBatchOperations++;
            commitIfNeeded(connection, transactionBatchSize,
                pendingBatchOperations);
            if (transactionBatchSize > 1
                && pendingBatchOperations >= transactionBatchSize) {
              pendingBatchOperations = 0;
            }
          } catch (Exception e) {
            failed++;
            rollbackIfNeeded(connection, transactionBatchSize);
            pendingBatchOperations = 0;
          } finally {
            latencies[i] = nanosToMicros(System.nanoTime() - opStarted);
          }
        }
        commitRemaining(connection, transactionBatchSize,
            pendingBatchOperations);
        long durationMillis = Math.max(1L,
            (System.nanoTime() - started) / 1_000_000L);
        Arrays.sort(latencies);
        double throughput = operations * 1000D / durationMillis;
        return new AdbBenchmarkResult("jdbc", workload, url, warmupOperations,
            operations, failed, durationMillis, throughput,
            percentile(latencies, 0.50D), percentile(latencies, 0.95D),
            percentile(latencies, 0.99D), latencies[latencies.length - 1],
            collectSqlDiagnostics());
      }
    }
  }

  /**
   * 执行一次本地 store benchmark。
   *
   * @param storeDir LdbStore 根目录
   * @param workload workload 名称
   * @param rows 读类 workload 的预置 key 数
   * @param warmupOperations 预热操作数
   * @param operations 正式统计操作数
   * @param rangeSize range scan 每次扫描范围
   * @return benchmark 结果
   * @throws Exception store 初始化或执行失败时抛出
   */
  public AdbBenchmarkResult executeStore(String storeDir, String workload,
      int rows, int warmupOperations, int operations, int rangeSize)
      throws Exception {
    requireSupportedWorkload(workload);
    try (LdbStore store = new LdbStore(storeDir)) {
      prepareStore(store, rows);
      for (int i = 0; i < warmupOperations; i++) {
        executeStoreOperation(store, workload, rows, rangeSize, i, false);
      }
      long[] latencies = new long[operations];
      long failed = 0;
      long started = System.nanoTime();
      for (int i = 0; i < operations; i++) {
        long opStarted = System.nanoTime();
        try {
          executeStoreOperation(store, workload, rows, rangeSize, i, true);
        } catch (Exception e) {
          failed++;
        } finally {
          latencies[i] = nanosToMicros(System.nanoTime() - opStarted);
        }
      }
      long durationMillis = Math.max(1L,
          (System.nanoTime() - started) / 1_000_000L);
      Arrays.sort(latencies);
      double throughput = operations * 1000D / durationMillis;
      return new AdbBenchmarkResult("store", workload, storeDir,
          warmupOperations, operations, failed, durationMillis, throughput,
          percentile(latencies, 0.50D), percentile(latencies, 0.95D),
          percentile(latencies, 0.99D), latencies[latencies.length - 1]);
    }
  }

  private static void prepareSchema(Connection connection, int rows,
      boolean dropTable) throws Exception {
    try (Statement statement = connection.createStatement()) {
      if (dropTable) {
        statement.execute("DROP TABLE IF EXISTS " + TABLE_NAME);
      }
      statement.execute("CREATE TABLE IF NOT EXISTS " + TABLE_NAME
          + "(ID BIGINT PRIMARY KEY, NAME VARCHAR) ENGINE \"adb_table\"");
    }
    try (PreparedStatement statement = connection.prepareStatement(
        "MERGE INTO " + TABLE_NAME + "(ID, NAME) KEY(ID) VALUES (?, ?)")) {
      for (int i = 1; i <= rows; i++) {
        statement.setLong(1, i);
        statement.setString(2, "name-" + i);
        statement.executeUpdate();
      }
    }
  }

  private static void executeOperation(BenchmarkStatements statements,
      String workload, int rows, int rangeSize, int index,
      boolean countedRun) throws Exception {
    if ("insert".equals(workload)) {
      long id = rows + (countedRun ? 1_000_000L : 100_000L) + index;
      statements.insert(id, "insert-" + id);
    } else if ("point_lookup".equals(workload)) {
      statements.pointLookup((index % rows) + 1L);
    } else if ("range_scan".equals(workload)) {
      long start = (index % rows) + 1L;
      statements.rangeScan(start, Math.min(rows, start + rangeSize - 1L));
    } else {
      int mode = index % 10;
      if (mode == 0) {
        long id = rows + (countedRun ? 2_000_000L : 200_000L) + index;
        statements.insert(id, "mixed-" + id);
      } else if (mode <= 7) {
        statements.pointLookup((index % rows) + 1L);
      } else {
        long start = (index % rows) + 1L;
        statements.rangeScan(start, Math.min(rows, start + rangeSize - 1L));
      }
    }
  }

  private static void commitIfNeeded(Connection connection,
      int transactionBatchSize, int operationCount) throws Exception {
    if (transactionBatchSize > 1 && operationCount % transactionBatchSize == 0) {
      connection.commit();
    }
  }

  private static void commitRemaining(Connection connection,
      int transactionBatchSize, int pendingOperations) throws Exception {
    if (transactionBatchSize > 1 && pendingOperations > 0) {
      connection.commit();
    }
  }

  private static void rollbackIfNeeded(Connection connection,
      int transactionBatchSize) throws Exception {
    if (transactionBatchSize > 1) {
      connection.rollback();
    }
  }

  private static void prepareStore(DbStore store, int rows) throws Exception {
    for (int i = 1; i <= rows; i++) {
      store.put(storeKey(i), storeValue(i));
    }
  }

  private static void executeStoreOperation(DbStore store, String workload,
      int rows, int rangeSize, int index, boolean countedRun)
      throws Exception {
    if ("insert".equals(workload)) {
      int id = rows + (countedRun ? 1_000_000 : 100_000) + index;
      store.put(storeKey(id), storeValue(id));
    } else if ("point_lookup".equals(workload)) {
      store.get(storeKey((index % rows) + 1));
    } else if ("range_scan".equals(workload)) {
      scanStoreRange(store, (index % rows) + 1,
          Math.min(rows, (index % rows) + rangeSize));
    } else {
      int mode = index % 10;
      if (mode == 0) {
        int id = rows + (countedRun ? 2_000_000 : 200_000) + index;
        store.put(storeKey(id), storeValue(id));
      } else if (mode <= 7) {
        store.get(storeKey((index % rows) + 1));
      } else {
        scanStoreRange(store, (index % rows) + 1,
            Math.min(rows, (index % rows) + rangeSize));
      }
    }
  }

  private static void scanStoreRange(DbStore store, int start, int end)
      throws Exception {
    byte[] lower = storeKey(start);
    byte[] upper = storeKey(end + 1);
    try (VersionScanSource scan = store.openVersionScanSource(
        ScanDirection.FORWARD)) {
      scan.seekToRangeStart(lower, upper);
      while (scan.isValid()) {
        scan.key();
        scan.value();
        scan.advance();
      }
    }
  }

  private static byte[] storeKey(int id) {
    return String.format("bench:%020d", id).getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] storeValue(int id) {
    return ("value-" + id).getBytes(StandardCharsets.UTF_8);
  }

  private static void write(Path output, AdbBenchmarkResult result)
      throws IOException {
    Path parent = output.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    try (OutputStream out = Files.newOutputStream(output)) {
      result.toProperties().store(out, "ADB benchmark result");
    }
  }

  private static Map<String, String> collectSqlDiagnostics() {
    Map<String, AdbSqlDiagnosticSnapshot> snapshots =
        AdbSqlDiagnosticsRegistry.snapshotAll();
    LinkedHashMap<String, String> details = new LinkedHashMap<>();
    long totalSqlCount = 0L;
    long failedSqlCount = 0L;
    long maxLatencyMillis = 0L;
    LinkedHashMap<String, AdbSqlOperationStats> merged =
        new LinkedHashMap<>();
    for (AdbSqlDiagnosticSnapshot snapshot : snapshots.values()) {
      totalSqlCount += snapshot.getTotalSqlCount();
      failedSqlCount += snapshot.getFailedSqlCount();
      maxLatencyMillis = Math.max(maxLatencyMillis,
          snapshot.getMaxLatencyMillis());
      for (AdbSqlOperationStats stats : snapshot.getOperationStats()
          .values()) {
        AdbSqlOperationStats previous = merged.get(stats.getOperation());
        if (previous == null) {
          merged.put(stats.getOperation(), stats);
        } else {
          merged.put(stats.getOperation(), new AdbSqlOperationStats(
              stats.getOperation(),
              previous.getCount() + stats.getCount(),
              previous.getFailedCount() + stats.getFailedCount(),
              previous.getTotalLatencyMillis()
                  + stats.getTotalLatencyMillis(),
              Math.max(previous.getMaxLatencyMillis(),
                  stats.getMaxLatencyMillis())));
        }
      }
    }
    details.put("sqlDiagnostics.totalSqlCount",
        String.valueOf(totalSqlCount));
    details.put("sqlDiagnostics.failedSqlCount",
        String.valueOf(failedSqlCount));
    details.put("sqlDiagnostics.maxLatencyMillis",
        String.valueOf(maxLatencyMillis));
    details.put("sqlDiagnostics.operationStats.count",
        String.valueOf(merged.size()));
    int index = 0;
    for (AdbSqlOperationStats stats : merged.values()) {
      String prefix = "sqlDiagnostics.operationStats." + index;
      details.put(prefix + ".operation", stats.getOperation());
      details.put(prefix + ".count", String.valueOf(stats.getCount()));
      details.put(prefix + ".failedCount",
          String.valueOf(stats.getFailedCount()));
      details.put(prefix + ".totalLatencyMillis",
          String.valueOf(stats.getTotalLatencyMillis()));
      details.put(prefix + ".avgLatencyMicros",
          String.valueOf(stats.getAverageLatencyMicros()));
      details.put(prefix + ".maxLatencyMillis",
          String.valueOf(stats.getMaxLatencyMillis()));
      index++;
    }
    return details;
  }

  private static long percentile(long[] sortedValues, double percentile) {
    if (sortedValues.length == 0) {
      return 0L;
    }
    int index = (int) Math.ceil(percentile * sortedValues.length) - 1;
    index = Math.max(0, Math.min(sortedValues.length - 1, index));
    return sortedValues[index];
  }

  private static long nanosToMicros(long nanos) {
    return Math.max(0L, nanos / 1_000L);
  }

  private static void requireSupportedWorkload(String workload) {
    if (!"insert".equals(workload) && !"point_lookup".equals(workload)
        && !"range_scan".equals(workload) && !"mixed".equals(workload)) {
      throw new IllegalArgumentException("Unsupported workload: " + workload);
    }
  }

  private static Map<String, String> parseArgs(String[] args) {
    Map<String, String> values = new HashMap<>();
    for (int i = 0; i < args.length; i += 2) {
      if (i + 1 >= args.length || !args[i].startsWith("--")) {
        throw new IllegalArgumentException("Illegal argument at index " + i);
      }
      values.put(args[i].substring(2), args[i + 1]);
    }
    return values;
  }

  private static String value(Map<String, String> values, String name,
      String defaultValue) {
    String value = values.get(name);
    return value == null || value.trim().isEmpty()
        ? defaultValue : value.trim();
  }

  private static boolean bool(Map<String, String> values, String name,
      boolean defaultValue) {
    String value = values.get(name);
    return value == null || value.trim().isEmpty()
        ? defaultValue : Boolean.parseBoolean(value.trim());
  }

  private static int positiveInt(Map<String, String> values, String name,
      int defaultValue) {
    int value = Integer.parseInt(value(values, name,
        String.valueOf(defaultValue)));
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }

  private static int nonNegativeInt(Map<String, String> values, String name,
      int defaultValue) {
    int value = Integer.parseInt(value(values, name,
        String.valueOf(defaultValue)));
    if (value < 0) {
      throw new IllegalArgumentException(name + " is negative");
    }
    return value;
  }

  private static final class BenchmarkStatements implements AutoCloseable {
    private final PreparedStatement insert;
    private final PreparedStatement pointLookup;
    private final PreparedStatement rangeScan;

    private BenchmarkStatements(Connection connection) throws Exception {
      this.insert = connection.prepareStatement("MERGE INTO " + TABLE_NAME
          + "(ID, NAME) KEY(ID) VALUES (?, ?)");
      this.pointLookup = connection.prepareStatement("SELECT NAME FROM "
          + TABLE_NAME + " WHERE ID = ?");
      this.rangeScan = connection.prepareStatement("SELECT COUNT(*) FROM "
          + TABLE_NAME + " WHERE ID BETWEEN ? AND ?");
    }

    private void insert(long id, String name) throws Exception {
      insert.setLong(1, id);
      insert.setString(2, name);
      insert.executeUpdate();
    }

    private void pointLookup(long id) throws Exception {
      pointLookup.setLong(1, id);
      try (ResultSet ignored = pointLookup.executeQuery()) {
        while (ignored.next()) {
          ignored.getString(1);
        }
      }
    }

    private void rangeScan(long start, long end) throws Exception {
      rangeScan.setLong(1, start);
      rangeScan.setLong(2, end);
      try (ResultSet ignored = rangeScan.executeQuery()) {
        while (ignored.next()) {
          ignored.getLong(1);
        }
      }
    }

    @Override
    public void close() throws Exception {
      rangeScan.close();
      pointLookup.close();
      insert.close();
    }
  }
}
