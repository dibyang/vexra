package net.xdob.vexra.adb;

import net.xdob.vexra.adb.db.RowCodec;
import net.xdob.vexra.adb.db.RowValue;
import net.xdob.vexra.adb.db.AdbTable;
import net.xdob.vexra.adb.db.Transaction2;
import net.xdob.vexra.adb.db.TxnManager;
import net.xdob.vexra.adb.db.VersionReadSession;
import net.xdob.vexra.adb.db.AdbSqlDiagnosticSnapshot;
import net.xdob.vexra.adb.db.AdbSqlDiagnosticsRegistry;
import net.xdob.vexra.adb.db.AdbSqlOperationStats;
import net.xdob.vexra.adb.db.AdbSqlPhaseStats;
import net.xdob.vexra.adb.h2plugin.AdbJdbcUrlPrefixProvider;
import net.xdob.vexra.adb.jdbc.AdbDriver;
import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.adb.key.VersionKey;
import net.xdob.vexra.adb.key.VersionRowKey;
import net.xdob.vexra.adb.ldb.LdbStore;
import net.xdob.vexra.ldb.util.Slice;
import org.h2.engine.Session;
import org.h2.engine.SessionLocal;
import org.h2.jdbc.JdbcConnection;
import org.h2.result.DefaultRow;
import org.h2.result.Row;
import org.h2.schema.Schema;
import org.h2.table.Table;
import org.h2.value.Value;
import org.h2.value.ValueBigint;
import org.h2.value.ValueRow;
import org.h2.value.ValueVarchar;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
  private static final String TABLE_ENGINE_ADB = "adb";
  private static final String TABLE_ENGINE_H2 = "h2";
  private static final String ALLOC_COUNT_CLOSED = "alloc_count_closed";
  private static final String ALLOC_SCAN_EMPTY = "alloc_scan_empty";
  private static final String ALLOC_SCAN_VIEW = "alloc_scan_view";
  private static final String ALLOC_SCAN_MATERIALIZE =
      "alloc_scan_materialize";
  private static final TabId STORE_ALLOCATION_TAB_ID = TabId.of(10_001, 1L);
  private static volatile long allocationBoundarySink;
  private static final ThreadLocal<MixedAllocationBreakdown>
      CURRENT_MIXED_ALLOCATION = new ThreadLocal<>();

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
    int threads = positiveInt(values, "threads", 1);
    int statementBatchSize = statementBatchSize(values, workload,
        transactionBatchSize);
    boolean dropTable = bool(values, "dropTable", true);
    boolean sqlDiagnostics = bool(values, "sqlDiagnostics", true);
    boolean secondaryIndex = bool(values, "secondaryIndex", false);
    String tableEngine = tableEngine(values);
    Path output = Paths.get(value(values, "output",
        "build/adb-benchmark/adb-benchmark.properties"));

    AdbBenchmarkMain benchmark = new AdbBenchmarkMain();
    AdbBenchmarkResult result;
    if ("jdbc".equals(mode)) {
      result = benchmark.executeJdbc(url, workload, rows, warmupOperations,
          operations, rangeSize, dropTable, transactionBatchSize,
          statementBatchSize, sqlDiagnostics, threads, tableEngine,
          secondaryIndex);
    } else if ("jdbc_bulk".equals(mode)) {
      result = benchmark.executeJdbcBulk(url, workload, rows,
          warmupOperations, operations, dropTable, transactionBatchSize,
          statementBatchSize, sqlDiagnostics, tableEngine, secondaryIndex);
    } else if ("txn".equals(mode)) {
      result = benchmark.executeTxn(storeDir, workload, rows,
          warmupOperations, operations, transactionBatchSize);
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
   * @param workload workload 名称：`insert`、`point_lookup`、`point_lookup_all`、`primary_find`、`table_count`、
   *                 `range_scan`、`range_count_local_write` 或 `mixed`
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
      boolean dropTable, int transactionBatchSize, int statementBatchSize,
      boolean sqlDiagnostics, int threads, String tableEngine,
      boolean secondaryIndex)
      throws Exception {
    requireSupportedWorkload(workload);
    loadJdbcDriver(url);
    String resolvedTableEngine = normalizeTableEngine(tableEngine);
    if (threads > 1) {
      return executeJdbcConcurrent(url, workload, rows, warmupOperations,
          operations, rangeSize, dropTable, transactionBatchSize,
          statementBatchSize, sqlDiagnostics, threads, resolvedTableEngine,
          secondaryIndex);
    }
    try (Connection connection = DriverManager.getConnection(url,
        new Properties())) {
      connection.setAutoCommit(transactionBatchSize <= 1);
      prepareSchema(connection, rows, dropTable, sqlDiagnostics,
          resolvedTableEngine, secondaryIndex);
      commitRemaining(connection, transactionBatchSize, 1);
      try (BenchmarkStatements statements = new BenchmarkStatements(
          connection)) {
        if ("insert".equals(workload) && statementBatchSize > 1) {
          executeInsertBatches(connection, statements, rows, warmupOperations,
              statementBatchSize, transactionBatchSize, false, null);
        } else {
          for (int i = 0; i < warmupOperations; i++) {
            executeOperation(statements, workload, rows, rangeSize, i, false,
                statementBatchSize);
            commitIfNeeded(connection, transactionBatchSize, i + 1);
          }
          commitRemaining(connection, transactionBatchSize, warmupOperations);
        }
        AdbSqlDiagnosticsRegistry.resetAll();
        long[] latencies = new long[operations];
        MixedLatencyBreakdown mixedLatency = newMixedLatencyBreakdown(
            workload, operations);
        MixedAllocationBreakdown mixedAllocation =
            newMixedAllocationBreakdown(workload);
        long failed = 0;
        int pendingBatchOperations = 0;
        long allocationStart = currentThreadAllocatedBytes();
        long started = System.nanoTime();
        if ("insert".equals(workload) && statementBatchSize > 1) {
          failed = executeInsertBatches(connection, statements, rows,
              operations, statementBatchSize, transactionBatchSize, true,
              latencies);
        } else {
          for (int i = 0; i < operations; i++) {
            long opStarted = System.nanoTime();
            BenchmarkOperationKind operationKind = mixedOperationKind(
                workload, i);
            try {
              long operationAllocationStart = currentThreadAllocatedBytes();
              CURRENT_MIXED_ALLOCATION.set(mixedAllocation);
              try {
                executeOperation(statements, workload, rows, rangeSize, i, true,
                    statementBatchSize);
              } finally {
                CURRENT_MIXED_ALLOCATION.remove();
              }
              recordMixedAllocation(mixedAllocation, operationKind,
                  operationAllocationStart, currentThreadAllocatedBytes());
              recordMixedLatency(mixedLatency, operationKind,
                  nanosToMicros(System.nanoTime() - opStarted));
              pendingBatchOperations++;
              long commitStarted = System.nanoTime();
              long commitAllocationStart = currentThreadAllocatedBytes();
              boolean committed = commitIfNeeded(connection,
                  transactionBatchSize, pendingBatchOperations);
              if (committed) {
                recordMixedAllocation(mixedAllocation,
                    BenchmarkOperationKind.COMMIT, commitAllocationStart,
                    currentThreadAllocatedBytes());
                recordMixedLatency(mixedLatency, BenchmarkOperationKind.COMMIT,
                    nanosToMicros(System.nanoTime() - commitStarted));
              }
              if (transactionBatchSize > 1
                  && pendingBatchOperations >= transactionBatchSize) {
                pendingBatchOperations = 0;
              }
            } catch (Exception e) {
              failed++;
              rollbackIfNeeded(connection, transactionBatchSize);
              pendingBatchOperations = 0;
            } finally {
              long latencyMicros = nanosToMicros(
                  System.nanoTime() - opStarted);
              latencies[i] = latencyMicros;
            }
          }
          commitRemaining(connection, transactionBatchSize,
              pendingBatchOperations);
        }
        long durationMillis = Math.max(1L,
            (System.nanoTime() - started) / 1_000_000L);
        Arrays.sort(latencies);
        double throughput = operations * 1000D / durationMillis;
        Map<String, String> details = collectSqlDiagnostics();
        details.put("tableEngine", resolvedTableEngine);
        details.put("secondaryIndex", String.valueOf(secondaryIndex));
        addThreadDetails(details, 1, throughput);
        addMixedLatencyDetails(details, mixedLatency);
        addMixedAllocationDetails(details, mixedAllocation);
        addAllocationDetails(details, allocationStart,
            currentThreadAllocatedBytes(), operations);
        return new AdbBenchmarkResult("jdbc", workload, url, warmupOperations,
            operations, failed, durationMillis, throughput,
            percentile(latencies, 0.50D), percentile(latencies, 0.95D),
            percentile(latencies, 0.99D), latencies[latencies.length - 1],
            details);
      }
    }
  }

  private AdbBenchmarkResult executeJdbcConcurrent(String url, String workload,
      int rows, int warmupOperations, int operations, int rangeSize,
      boolean dropTable, int transactionBatchSize, int statementBatchSize,
      boolean sqlDiagnostics, int threads, String tableEngine,
      boolean secondaryIndex)
      throws Exception {
    try (Connection connection = DriverManager.getConnection(url,
        new Properties())) {
      connection.setAutoCommit(transactionBatchSize <= 1);
      prepareSchema(connection, rows, dropTable, sqlDiagnostics, tableEngine,
          secondaryIndex);
      commitRemaining(connection, transactionBatchSize, 1);
    }
    AdbSqlDiagnosticsRegistry.resetAll();
    final int countedPerThread = operations / threads;
    final int countedRemainder = operations % threads;
    final int warmupPerThread = warmupOperations / threads;
    final int warmupRemainder = warmupOperations % threads;
    final long[] latencies = new long[operations];
    final MixedLatencyBreakdown mixedLatency = newMixedLatencyBreakdown(
        workload, operations);
    final MixedAllocationBreakdown mixedAllocation =
        newMixedAllocationBreakdown(workload);
    final AtomicLong failed = new AtomicLong();
    final AtomicLong completed = new AtomicLong();
    final AtomicLong allocatedBytes = new AtomicLong();
    final AtomicReference<Throwable> firstFailure = new AtomicReference<>();
    final CountDownLatch start = new CountDownLatch(1);
    final CountDownLatch ready = new CountDownLatch(threads);
    final CountDownLatch measureStart = new CountDownLatch(1);
    final CountDownLatch done = new CountDownLatch(threads);

    for (int t = 0; t < threads; t++) {
      final int threadIndex = t;
      final int countedOps = countedPerThread
          + (threadIndex < countedRemainder ? 1 : 0);
      final int warmupOps = warmupPerThread
          + (threadIndex < warmupRemainder ? 1 : 0);
      final int latencyOffset = countedPerThread * threadIndex
          + Math.min(threadIndex, countedRemainder);
      Thread worker = new Thread(() -> {
        try {
          start.await();
          executeJdbcWorker(url, workload, rows, rangeSize, warmupOps,
              countedOps, latencyOffset, statementBatchSize,
              transactionBatchSize, latencies, failed, completed,
              allocatedBytes, ready, measureStart,
              threadIndex, warmupOperations, mixedLatency, mixedAllocation);
        } catch (Throwable e) {
          firstFailure.compareAndSet(null, e);
          failed.addAndGet(countedOps);
          ready.countDown();
        } finally {
          done.countDown();
        }
      }, "adb-benchmark-" + threadIndex);
      worker.start();
    }

    start.countDown();
    try {
      ready.await();
      AdbSqlDiagnosticsRegistry.resetAll();
      long started = System.nanoTime();
      measureStart.countDown();
      done.await();
      long durationMillis = Math.max(1L,
          (System.nanoTime() - started) / 1_000_000L);
      Throwable failure = firstFailure.get();
      if (failure != null) {
        if (failure instanceof Exception) {
          throw (Exception) failure;
        }
        throw new RuntimeException(failure);
      }
      Arrays.sort(latencies);
      double throughput = operations * 1000D / durationMillis;
      Map<String, String> details = collectSqlDiagnostics();
      details.put("tableEngine", tableEngine);
      details.put("secondaryIndex", String.valueOf(secondaryIndex));
      addThreadDetails(details, threads, throughput);
      details.put("concurrency.completedOperations",
          String.valueOf(completed.get()));
      details.put("concurrency.measuredWindow", "operationsOnly");
      addMixedLatencyDetails(details, mixedLatency);
      addMixedAllocationDetails(details, mixedAllocation);
      addAllocationDetails(details, allocatedBytes.get(), operations);
      return new AdbBenchmarkResult("jdbc", workload, url, warmupOperations,
          operations, failed.get(), durationMillis, throughput,
          percentile(latencies, 0.50D), percentile(latencies, 0.95D),
          percentile(latencies, 0.99D), latencies[latencies.length - 1],
          details);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw e;
    }
  }

  private static void executeJdbcWorker(String url, String workload, int rows,
      int rangeSize, int warmupOperations, int operations, int latencyOffset,
      int statementBatchSize, int transactionBatchSize, long[] latencies,
      AtomicLong failed, AtomicLong completed, AtomicLong allocatedBytes,
      CountDownLatch ready, CountDownLatch measureStart,
      int threadIndex,
      int totalWarmupOperations, MixedLatencyBreakdown mixedLatency,
      MixedAllocationBreakdown mixedAllocation)
      throws Exception {
    try (Connection connection = DriverManager.getConnection(url,
        new Properties())) {
      connection.setAutoCommit(transactionBatchSize <= 1);
      try (BenchmarkStatements statements = new BenchmarkStatements(
          connection)) {
        for (int i = 0; i < warmupOperations; i++) {
          int warmupIndex = threadIndex * Math.max(1, totalWarmupOperations)
              + i;
          executeOperation(statements, workload, rows, rangeSize,
              warmupIndex, false, statementBatchSize);
          commitIfNeeded(connection, transactionBatchSize, i + 1);
        }
        commitRemaining(connection, transactionBatchSize, warmupOperations);

        int pendingBatchOperations = 0;
        ready.countDown();
        measureStart.await();
        long allocationStart = currentThreadAllocatedBytes();
        try {
          for (int i = 0; i < operations; i++) {
            long opStarted = System.nanoTime();
            int index = latencyOffset + i;
            BenchmarkOperationKind operationKind = mixedOperationKind(
                workload, index);
            try {
              long operationAllocationStart = currentThreadAllocatedBytes();
              CURRENT_MIXED_ALLOCATION.set(mixedAllocation);
              try {
                executeOperation(statements, workload, rows, rangeSize, index,
                    true, statementBatchSize);
              } finally {
                CURRENT_MIXED_ALLOCATION.remove();
              }
              recordMixedAllocation(mixedAllocation, operationKind,
                  operationAllocationStart, currentThreadAllocatedBytes());
              recordMixedLatency(mixedLatency, operationKind,
                  nanosToMicros(System.nanoTime() - opStarted));
              pendingBatchOperations++;
              long commitStarted = System.nanoTime();
              long commitAllocationStart = currentThreadAllocatedBytes();
              boolean committed = commitIfNeeded(connection,
                  transactionBatchSize, pendingBatchOperations);
              if (committed) {
                recordMixedAllocation(mixedAllocation,
                    BenchmarkOperationKind.COMMIT, commitAllocationStart,
                    currentThreadAllocatedBytes());
                recordMixedLatency(mixedLatency, BenchmarkOperationKind.COMMIT,
                    nanosToMicros(System.nanoTime() - commitStarted));
              }
              if (transactionBatchSize > 1
                  && pendingBatchOperations >= transactionBatchSize) {
                pendingBatchOperations = 0;
              }
              completed.incrementAndGet();
            } catch (Exception e) {
              failed.incrementAndGet();
              rollbackIfNeeded(connection, transactionBatchSize);
              pendingBatchOperations = 0;
            } finally {
              long latencyMicros = nanosToMicros(
                  System.nanoTime() - opStarted);
              latencies[latencyOffset + i] = latencyMicros;
            }
          }
          commitRemaining(connection, transactionBatchSize,
              pendingBatchOperations);
        } finally {
          addAllocationBytes(allocatedBytes, allocationStart,
              currentThreadAllocatedBytes());
        }
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
      prepareStore(store, workload, rows);
      try (VersionReadSession readSession = store.openVersionReadSession()) {
        for (int i = 0; i < warmupOperations; i++) {
          executeStoreOperation(store, readSession, workload, rows, rangeSize,
              i, false);
        }
        long[] latencies = new long[operations];
        long failed = 0;
        long allocationStart = currentThreadAllocatedBytes();
        long started = System.nanoTime();
        for (int i = 0; i < operations; i++) {
          long opStarted = System.nanoTime();
          try {
            executeStoreOperation(store, readSession, workload, rows, rangeSize,
                i, true);
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
        Map<String, String> details = new LinkedHashMap<>();
        addAllocationDetails(details, allocationStart,
            currentThreadAllocatedBytes(), operations);
        return new AdbBenchmarkResult("store", workload, storeDir,
            warmupOperations, operations, failed, durationMillis, throughput,
            percentile(latencies, 0.50D), percentile(latencies, 0.95D),
            percentile(latencies, 0.99D), latencies[latencies.length - 1],
            details);
      }
    }
  }

  /**
   * 执行 JDBC 连接下的 ADB bulk insert benchmark。
   *
   * <p>该模式仍通过 JDBC URL 建库建表，并复用当前 JDBC session 的事务事件提交 ADB
   * 事务；区别是插入阶段直接命中 ADB table bulk API，避免 H2 SQL executor 对多 values
   * insert 的逐行 `Table.addRow` 调度。</p>
   */
  public AdbBenchmarkResult executeJdbcBulk(String url, String workload,
      int rows, int warmupOperations, int operations, boolean dropTable,
      int transactionBatchSize, int statementBatchSize,
      boolean sqlDiagnostics, String tableEngine, boolean secondaryIndex)
      throws Exception {
    if (!"insert".equals(workload)) {
      throw new IllegalArgumentException(
          "jdbc_bulk mode only supports insert workload: " + workload);
    }
    String resolvedTableEngine = normalizeTableEngine(tableEngine);
    if (!TABLE_ENGINE_ADB.equals(resolvedTableEngine)) {
      throw new IllegalArgumentException(
          "jdbc_bulk mode only supports ADB table engine: "
              + resolvedTableEngine);
    }
    loadJdbcDriver(url);
    try (Connection connection = DriverManager.getConnection(url,
        new Properties())) {
      connection.setAutoCommit(false);
      prepareSchema(connection, rows, dropTable, sqlDiagnostics,
          resolvedTableEngine, secondaryIndex);
      connection.commit();
      AdbTable table = adbBenchmarkTable(connection);

      executeJdbcBulkBatches(connection, table, rows, warmupOperations,
          statementBatchSize, transactionBatchSize, false, null);
      AdbSqlDiagnosticsRegistry.resetAll();

      long[] latencies = new long[operations];
      long allocationStart = currentThreadAllocatedBytes();
      long started = System.nanoTime();
      long failed = executeJdbcBulkBatches(connection, table, rows, operations,
          statementBatchSize, transactionBatchSize, true, latencies);
      long durationMillis = Math.max(1L,
          (System.nanoTime() - started) / 1_000_000L);
      Arrays.sort(latencies);
      double throughput = operations * 1000D / durationMillis;
      Map<String, String> details = collectSqlDiagnostics();
      details.put("tableEngine", resolvedTableEngine);
      details.put("secondaryIndex", String.valueOf(secondaryIndex));
      addAllocationDetails(details, allocationStart,
          currentThreadAllocatedBytes(), operations);
      return new AdbBenchmarkResult("jdbc_bulk", workload, url,
          warmupOperations, operations, failed, durationMillis, throughput,
          percentile(latencies, 0.50D), percentile(latencies, 0.95D),
          percentile(latencies, 0.99D), latencies[latencies.length - 1],
          details);
    }
  }

  private static long executeJdbcBulkBatches(Connection connection,
      AdbTable table, int rows, int operations, int statementBatchSize,
      int transactionBatchSize, boolean countedRun, long[] latencies)
      throws Exception {
    long failed = 0L;
    int completedInTransaction = 0;
    SessionLocal session = sessionLocal(connection);
    int index = 0;
    while (index < operations) {
      int batchSize = Math.min(statementBatchSize, operations - index);
      long opStarted = System.nanoTime();
      try {
        long firstId = rows + (countedRun ? 1_000_000L : 100_000L) + index;
        table.bulkInsertAppendRows(session, rows(firstId, batchSize,
            countedRun ? "insert-" : "warmup-insert-"));
        completedInTransaction += batchSize;
        commitIfNeeded(connection, transactionBatchSize,
            completedInTransaction);
        if (completedInTransaction >= transactionBatchSize) {
          completedInTransaction = 0;
        }
      } catch (Exception e) {
        failed += batchSize;
        connection.rollback();
        completedInTransaction = 0;
      } finally {
        if (latencies != null) {
          long perRowMicros = nanosToMicros(System.nanoTime() - opStarted)
              / Math.max(1, batchSize);
          for (int i = 0; i < batchSize; i++) {
            latencies[index + i] = perRowMicros;
          }
        }
      }
      index += batchSize;
    }
    commitRemaining(connection, transactionBatchSize, completedInTransaction);
    return failed;
  }

  private static AdbTable adbBenchmarkTable(Connection connection)
      throws Exception {
    SessionLocal session = sessionLocal(connection);
    Schema schema = session.getDatabase().getSchema(
        session.getCurrentSchemaName());
    Table table = schema.findTableOrView(session, TABLE_NAME);
    if (!(table instanceof AdbTable)) {
      throw new IllegalStateException("ADB benchmark table is not AdbTable: "
          + table);
    }
    return (AdbTable) table;
  }

  private static SessionLocal sessionLocal(Connection connection)
      throws Exception {
    Session session = connection.unwrap(JdbcConnection.class).getSession();
    if (!(session instanceof SessionLocal)) {
      throw new IllegalStateException("Unsupported H2 session type: "
          + session.getClass().getName());
    }
    return (SessionLocal) session;
  }

  private static void loadJdbcDriver(String url) throws Exception {
    if (url != null && url.regionMatches(true, 0,
        AdbJdbcUrlPrefixProvider.URL_PREFIX, 0,
        AdbJdbcUrlPrefixProvider.URL_PREFIX.length())) {
      Class.forName(AdbDriver.class.getName());
      return;
    }
    Class.forName("org.h2.Driver");
  }

  private static List<Row> rows(long firstId, int batchSize,
      String namePrefix) {
    ArrayList<Row> rows = new ArrayList<>(batchSize);
    for (int i = 0; i < batchSize; i++) {
      long id = firstId + i;
      DefaultRow row = new DefaultRow(new Value[]{
          ValueBigint.get(id),
          ValueVarchar.get(namePrefix + id)
      });
      row.setKey(id);
      rows.add(row);
    }
    return rows;
  }

  /**
   * 执行 ADB 本地事务层 benchmark。
   *
   * <p>该模式绕过 H2 SQL parser 和 table engine，但仍然使用 TxnManager、MVCC key、
   * RowCodec、row-count meta 和底层 ldb 提交路径，用于区分 ADB 事务写入能力与 JDBC
   * 逐行 table engine 调度成本。</p>
   */
  public AdbBenchmarkResult executeTxn(String storeDir, String workload,
      int rows, int warmupOperations, int operations, int transactionBatchSize)
      throws Exception {
    if (!"insert".equals(workload)) {
      throw new IllegalArgumentException(
          "Txn mode only supports insert workload: " + workload);
    }
    try (LdbStore store = new LdbStore(storeDir)) {
      TxnManager txnManager = new TxnManager(store);
      TabId tableId = TabId.of(1, 0L);
      executeTxnInserts(txnManager, tableId, rows, warmupOperations,
          transactionBatchSize, false, null);

      long[] latencies = new long[operations];
      long allocationStart = currentThreadAllocatedBytes();
      long started = System.nanoTime();
      long failed = executeTxnInserts(txnManager, tableId, rows, operations,
          transactionBatchSize, true, latencies);
      long durationMillis = Math.max(1L,
          (System.nanoTime() - started) / 1_000_000L);
      Arrays.sort(latencies);
      double throughput = operations * 1000D / durationMillis;
      Map<String, String> details = new LinkedHashMap<>();
      addAllocationDetails(details, allocationStart,
          currentThreadAllocatedBytes(), operations);
      return new AdbBenchmarkResult("txn", workload, storeDir,
          warmupOperations, operations, failed, durationMillis, throughput,
          percentile(latencies, 0.50D), percentile(latencies, 0.95D),
          percentile(latencies, 0.99D), latencies[latencies.length - 1],
          details);
    }
  }

  private static long executeTxnInserts(TxnManager txnManager, TabId tableId,
      int rows, int operations, int transactionBatchSize, boolean countedRun,
      long[] latencies) throws Exception {
    long failed = 0L;
    int completedInTransaction = 0;
    Transaction2 txn = txnManager.beginTransaction();
    try {
      for (int i = 0; i < operations; i++) {
        long opStarted = System.nanoTime();
        try {
          long id = rows + (countedRun ? 1_000_000L : 100_000L) + i;
          txnManager.put(txn, RowKey.of(tableId, id), rowValue(txn, id,
              countedRun ? "insert-" + id : "warmup-insert-" + id), null);
          completedInTransaction++;
          if (completedInTransaction >= transactionBatchSize) {
            txnManager.commit(txn);
            txn = txnManager.beginTransaction();
            completedInTransaction = 0;
          }
        } catch (Exception e) {
          failed++;
          txnManager.rollback(txn);
          txn = txnManager.beginTransaction();
          completedInTransaction = 0;
        } finally {
          if (latencies != null) {
            latencies[i] = nanosToMicros(System.nanoTime() - opStarted);
          }
        }
      }
      if (completedInTransaction > 0) {
        txnManager.commit(txn);
      } else {
        txnManager.rollback(txn);
      }
      return failed;
    } catch (Exception e) {
      txnManager.rollback(txn);
      throw e;
    }
  }

  private static RowValue rowValue(Transaction2 txn, long id, String name) {
    RowValue rowValue = new RowValue();
    rowValue.txnId = txn.getTxnId();
    rowValue.commitTs = 0L;
    rowValue.deleted = false;
    rowValue.payload = RowCodec.encode(ValueRow.get(new Value[]{
        ValueBigint.get(id),
        ValueVarchar.get(name)
    }));
    return rowValue;
  }

  private static void prepareSchema(Connection connection, int rows,
      boolean dropTable, boolean sqlDiagnostics, String tableEngine,
      boolean secondaryIndex) throws Exception {
    String resolvedTableEngine = normalizeTableEngine(tableEngine);
    try (Statement statement = connection.createStatement()) {
      if (dropTable) {
        statement.execute("DROP TABLE IF EXISTS " + TABLE_NAME);
      }
      if (TABLE_ENGINE_ADB.equals(resolvedTableEngine)) {
        String diagnosticsParam = sqlDiagnostics ? ""
            : " WITH \"adb.sql.diagnostics=false\"";
        statement.execute("CREATE TABLE IF NOT EXISTS " + TABLE_NAME
            + "(ID BIGINT PRIMARY KEY, NAME VARCHAR) ENGINE \"adb_table\""
            + diagnosticsParam);
      } else {
        statement.execute("CREATE TABLE IF NOT EXISTS " + TABLE_NAME
            + "(ID BIGINT PRIMARY KEY, NAME VARCHAR)");
      }
      if (secondaryIndex) {
        statement.execute("CREATE INDEX IF NOT EXISTS IDX_" + TABLE_NAME
            + "_NAME ON " + TABLE_NAME + "(NAME)");
      }
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
      boolean countedRun, int statementBatchSize) throws Exception {
    if ("insert".equals(workload)) {
      long id = rows + (countedRun ? 1_000_000L : 100_000L) + index;
      statements.insert(id, "insert-" + id);
    } else if ("point_lookup".equals(workload)) {
      statements.pointLookup((index % rows) + 1L);
    } else if ("point_lookup_all".equals(workload)) {
      statements.pointLookupAll((index % rows) + 1L);
    } else if ("primary_find".equals(workload)) {
      statements.primaryFind((index % rows) + 1L);
    } else if ("table_count".equals(workload)) {
      statements.tableCount();
    } else if ("range_scan".equals(workload)) {
      long start = (index % rows) + 1L;
      statements.rangeScan(start, Math.min(rows, start + rangeSize - 1L));
    } else if ("range_count_local_write".equals(workload)) {
      long id = rows + (countedRun ? 3_000_000L : 300_000L) + index;
      statements.rangeCountWithLocalWrite(id, "local-range-" + id);
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

  private static long executeInsertBatches(Connection connection,
      BenchmarkStatements statements, int rows, int operations,
      int statementBatchSize, int transactionBatchSize, boolean countedRun,
      long[] latencies) throws Exception {
    long failed = 0L;
    int completedInTransaction = 0;
    int index = 0;
    while (index < operations) {
      int batchSize = Math.min(statementBatchSize, operations - index);
      long opStarted = System.nanoTime();
      try {
        long firstId = rows + (countedRun ? 1_000_000L : 100_000L) + index;
        statements.insertMany(firstId, batchSize, countedRun
            ? "insert-" : "warmup-insert-");
        completedInTransaction += batchSize;
        commitIfNeeded(connection, transactionBatchSize,
            completedInTransaction);
        if (transactionBatchSize > 1
            && completedInTransaction >= transactionBatchSize) {
          completedInTransaction = 0;
        }
      } catch (Exception e) {
        failed += batchSize;
        rollbackIfNeeded(connection, transactionBatchSize);
        completedInTransaction = 0;
      } finally {
        if (latencies != null) {
          long perRowMicros = nanosToMicros(System.nanoTime() - opStarted)
              / Math.max(1, batchSize);
          for (int i = 0; i < batchSize; i++) {
            latencies[index + i] = perRowMicros;
          }
        }
      }
      index += batchSize;
    }
    commitRemaining(connection, transactionBatchSize, completedInTransaction);
    return failed;
  }

  private static boolean commitIfNeeded(Connection connection,
      int transactionBatchSize, int operationCount) throws Exception {
    if (transactionBatchSize > 1 && operationCount % transactionBatchSize == 0) {
      connection.commit();
      return true;
    }
    return false;
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

  private static void prepareStore(DbStore store, String workload, int rows)
      throws Exception {
    for (int i = 1; i <= rows; i++) {
      if (isAllocationBoundaryWorkload(workload)) {
        store.put(storeMaterializedKey(i), storeMaterializedValue(i));
      } else {
        store.put(storeKey(i), storeValue(i));
      }
    }
  }

  private static void executeStoreOperation(DbStore store,
      VersionReadSession readSession, String workload, int rows, int rangeSize,
      int index, boolean countedRun)
      throws Exception {
    if ("insert".equals(workload)) {
      int id = rows + (countedRun ? 1_000_000 : 100_000) + index;
      store.put(storeKey(id), storeValue(id));
    } else if ("point_lookup".equals(workload)) {
      store.get(storeKey((index % rows) + 1));
    } else if ("point_lookup_all".equals(workload)) {
      store.get(storeKey((index % rows) + 1));
    } else if ("primary_find".equals(workload)) {
      store.get(storeKey((index % rows) + 1));
    } else if ("table_count".equals(workload)) {
      scanStoreRange(readSession, 1, rows);
    } else if ("range_scan".equals(workload)) {
      scanStoreRange(readSession, (index % rows) + 1,
          Math.min(rows, (index % rows) + rangeSize));
    } else if ("range_count_local_write".equals(workload)) {
      int id = rows + (countedRun ? 3_000_000 : 300_000) + index;
      store.put(storeKey(id), storeValue(id));
      scanStoreRange(readSession, id, id);
    } else if (ALLOC_COUNT_CLOSED.equals(workload)) {
      scanStoreAllocationCountClosed(readSession, (index % rows) + 1,
          Math.min(rows, (index % rows) + rangeSize));
    } else if (ALLOC_SCAN_EMPTY.equals(workload)) {
      scanStoreAllocationEmptyVisitor(readSession, (index % rows) + 1,
          Math.min(rows, (index % rows) + rangeSize));
    } else if (ALLOC_SCAN_VIEW.equals(workload)) {
      scanStoreAllocationViewOnly(readSession, (index % rows) + 1,
          Math.min(rows, (index % rows) + rangeSize));
    } else if (ALLOC_SCAN_MATERIALIZE.equals(workload)) {
      scanStoreAllocationMaterialize(readSession, (index % rows) + 1,
          Math.min(rows, (index % rows) + rangeSize));
    } else {
      int mode = index % 10;
      if (mode == 0) {
        int id = rows + (countedRun ? 2_000_000 : 200_000) + index;
        store.put(storeKey(id), storeValue(id));
      } else if (mode <= 7) {
        store.get(storeKey((index % rows) + 1));
      } else {
        scanStoreRange(readSession, (index % rows) + 1,
            Math.min(rows, (index % rows) + rangeSize));
      }
    }
  }

  private static void scanStoreRange(VersionReadSession readSession, int start,
      int end)
      throws Exception {
    byte[] lower = storeKey(start);
    byte[] upper = storeKey(end);
    readSession.countClosed(lower, upper);
  }

  private static void scanStoreAllocationCountClosed(
      VersionReadSession readSession, int start, int end) {
    readSession.countClosed(storeMaterializedKey(start),
        storeMaterializedKey(end));
  }

  private static void scanStoreAllocationEmptyVisitor(
      VersionReadSession readSession, int start, int end) {
    readSession.scanClosed(storeMaterializedKey(start),
        storeMaterializedKey(end), EmptyAllocationVisitor.INSTANCE);
  }

  private static void scanStoreAllocationViewOnly(
      VersionReadSession readSession, int start, int end) {
    readSession.scanClosed(storeMaterializedKey(start),
        storeMaterializedKey(end), ViewOnlyAllocationVisitor.INSTANCE);
  }

  private static void scanStoreAllocationMaterialize(
      VersionReadSession readSession, int start, int end) {
    readSession.scanClosed(storeMaterializedKey(start),
        storeMaterializedKey(end), MaterializingAllocationVisitor.INSTANCE);
  }

  private static byte[] storeKey(int id) {
    return String.format("bench:%020d", id).getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] storeValue(int id) {
    return ("value-" + id).getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] storeMaterializedKey(long id) {
    return VersionRowKey.of(STORE_ALLOCATION_TAB_ID, id, true, id).toBytes();
  }

  private static byte[] storeMaterializedValue(long id) {
    RowValue rowValue = new RowValue();
    rowValue.txnId = 0L;
    rowValue.commitTs = id;
    rowValue.deleted = false;
    rowValue.payload = RowCodec.encode(ValueRow.get(new Value[]{
        ValueBigint.get(id),
        ValueVarchar.get("alloc-" + id)
    }));
    return RowValue.encodeValue(rowValue);
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
    LinkedHashMap<String, AdbSqlPhaseStats> mergedPhases =
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
      for (AdbSqlPhaseStats stats : snapshot.getPhaseStats().values()) {
        AdbSqlPhaseStats previous = mergedPhases.get(stats.getPhase());
        if (previous == null) {
          mergedPhases.put(stats.getPhase(), stats);
        } else {
          mergedPhases.put(stats.getPhase(), new AdbSqlPhaseStats(
              stats.getPhase(),
              previous.getCount() + stats.getCount(),
              previous.getTotalLatencyMicros()
                  + stats.getTotalLatencyMicros(),
              Math.max(previous.getMaxLatencyMicros(),
                  stats.getMaxLatencyMicros())));
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
    details.put("sqlDiagnostics.phaseStats.count",
        String.valueOf(mergedPhases.size()));
    index = 0;
    for (AdbSqlPhaseStats stats : mergedPhases.values()) {
      String prefix = "sqlDiagnostics.phaseStats." + index;
      details.put(prefix + ".phase", stats.getPhase());
      details.put(prefix + ".count", String.valueOf(stats.getCount()));
      details.put(prefix + ".totalLatencyMicros",
          String.valueOf(stats.getTotalLatencyMicros()));
      details.put(prefix + ".avgLatencyMicros",
          String.valueOf(stats.getAverageLatencyMicros()));
      details.put(prefix + ".maxLatencyMicros",
          String.valueOf(stats.getMaxLatencyMicros()));
      index++;
    }
    return details;
  }

  private static void addThreadDetails(Map<String, String> details,
      int threads, double throughput) {
    details.put("concurrency.threads", String.valueOf(threads));
    details.put("concurrency.perThreadThroughputPerSecond",
        String.valueOf(throughput / Math.max(1, threads)));
  }

  private static long currentThreadAllocatedBytes() {
    java.lang.management.ThreadMXBean bean =
        ManagementFactory.getThreadMXBean();
    if (!(bean instanceof com.sun.management.ThreadMXBean)) {
      return -1L;
    }
    com.sun.management.ThreadMXBean allocationBean =
        (com.sun.management.ThreadMXBean) bean;
    if (!allocationBean.isThreadAllocatedMemorySupported()) {
      return -1L;
    }
    if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
      try {
        allocationBean.setThreadAllocatedMemoryEnabled(true);
      } catch (SecurityException e) {
        return -1L;
      }
    }
    if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
      return -1L;
    }
    return allocationBean.getThreadAllocatedBytes(
        Thread.currentThread().getId());
  }

  private static void addAllocationBytes(AtomicLong allocatedBytes,
      long allocationStart, long allocationEnd) {
    if (allocationStart >= 0L && allocationEnd >= allocationStart) {
      allocatedBytes.addAndGet(allocationEnd - allocationStart);
    }
  }

  private static void addAllocationDetails(Map<String, String> details,
      long allocationStart, long allocationEnd, long operations) {
    if (allocationStart >= 0L && allocationEnd >= allocationStart) {
      addAllocationDetails(details, allocationEnd - allocationStart,
          operations);
      return;
    }
    details.put("allocation.supported", "false");
  }

  private static void addAllocationDetails(Map<String, String> details,
      long totalBytes, long operations) {
    if (totalBytes < 0L) {
      details.put("allocation.supported", "false");
      return;
    }
    details.put("allocation.supported", "true");
    details.put("allocation.totalBytes", String.valueOf(totalBytes));
    details.put("allocation.bytesPerOperation",
        String.valueOf(totalBytes / Math.max(1L, operations)));
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

  private static MixedLatencyBreakdown newMixedLatencyBreakdown(
      String workload, int operations) {
    return "mixed".equals(workload)
        ? new MixedLatencyBreakdown(operations) : null;
  }

  private static MixedAllocationBreakdown newMixedAllocationBreakdown(
      String workload) {
    return "mixed".equals(workload) ? new MixedAllocationBreakdown() : null;
  }

  private static BenchmarkOperationKind mixedOperationKind(String workload,
      int index) {
    if (!"mixed".equals(workload)) {
      return BenchmarkOperationKind.OTHER;
    }
    int mode = index % 10;
    if (mode == 0) {
      return BenchmarkOperationKind.WRITE;
    }
    if (mode <= 7) {
      return BenchmarkOperationKind.POINT_LOOKUP;
    }
    return BenchmarkOperationKind.RANGE_COUNT;
  }

  private static void recordMixedLatency(MixedLatencyBreakdown mixedLatency,
      BenchmarkOperationKind operationKind, long latencyMicros) {
    if (mixedLatency != null) {
      mixedLatency.record(operationKind, latencyMicros);
    }
  }

  private static void recordMixedAllocation(
      MixedAllocationBreakdown mixedAllocation,
      BenchmarkOperationKind operationKind, long allocationStart,
      long allocationEnd) {
    if (mixedAllocation != null && allocationStart >= 0L
        && allocationEnd >= allocationStart) {
      mixedAllocation.record(operationKind, allocationEnd - allocationStart);
    }
  }

  private static void recordCurrentMixedStage(
      BenchmarkAllocationStage stage, long allocationStart,
      long allocationEnd) {
    recordCurrentMixedStage(stage.name(), allocationStart, allocationEnd);
  }

  public static long benchmarkAllocationBytes() {
    return currentThreadAllocatedBytes();
  }

  public static void recordCurrentMixedStage(String stage,
      long allocationStart, long allocationEnd) {
    MixedAllocationBreakdown mixedAllocation =
        CURRENT_MIXED_ALLOCATION.get();
    if (mixedAllocation != null && allocationStart >= 0L
        && allocationEnd >= allocationStart) {
      mixedAllocation.recordStage(stage, allocationEnd - allocationStart);
    }
  }

  private static void addMixedLatencyDetails(Map<String, String> details,
      MixedLatencyBreakdown mixedLatency) {
    if (mixedLatency != null) {
      mixedLatency.addDetails(details);
    }
  }

  private static void addMixedAllocationDetails(Map<String, String> details,
      MixedAllocationBreakdown mixedAllocation) {
    if (mixedAllocation != null) {
      mixedAllocation.addDetails(details);
    }
  }

  private static void requireSupportedWorkload(String workload) {
    if (!"insert".equals(workload) && !"point_lookup".equals(workload)
        && !"point_lookup_all".equals(workload)
        && !"primary_find".equals(workload)
        && !"table_count".equals(workload)
        && !"range_scan".equals(workload)
        && !"range_count_local_write".equals(workload)
        && !isAllocationBoundaryWorkload(workload)
        && !"mixed".equals(workload)) {
      throw new IllegalArgumentException("Unsupported workload: " + workload);
    }
  }

  private static boolean isAllocationBoundaryWorkload(String workload) {
    return ALLOC_COUNT_CLOSED.equals(workload)
        || ALLOC_SCAN_EMPTY.equals(workload)
        || ALLOC_SCAN_VIEW.equals(workload)
        || ALLOC_SCAN_MATERIALIZE.equals(workload);
  }

  private enum EmptyAllocationVisitor implements VersionReadSession.EntryVisitor {
    INSTANCE;

    @Override
    public void visit(Slice keyView, Slice valueView) {
      // 空 visitor 用于隔离 LDB cursor/visitor 边界成本。
    }
  }

  private enum ViewOnlyAllocationVisitor implements VersionReadSession.EntryVisitor {
    INSTANCE;

    @Override
    public void visit(Slice keyView, Slice valueView) {
      allocationBoundarySink += keyView.length() + valueView.length();
    }
  }

  private enum MaterializingAllocationVisitor
      implements VersionReadSession.EntryVisitor {
    INSTANCE;

    @Override
    public void visit(Slice keyView, Slice valueView) {
      VersionKey versionKey = VersionKey.fromBytes(keyView.copyBytes());
      RowValue rowValue = RowValue.decodeValueView(valueView);
      if (rowValue == null || rowValue.payload == null) {
        return;
      }
      Value[] values = new Value[]{
          RowCodec.decodeColumn(rowValue.payload, 0),
          RowCodec.decodeColumn(rowValue.payload, 1)
      };
      DefaultRow row = new DefaultRow(values);
      row.setKey(versionKey.getRowId());
      allocationBoundarySink += row.getKey()
          + values[0].getLong()
          + values[1].getString().length();
    }
  }

  private enum BenchmarkOperationKind {
    WRITE,
    POINT_LOOKUP,
    RANGE_COUNT,
    COMMIT,
    OTHER
  }

  private enum BenchmarkAllocationStage {
    PARAMETER_SET,
    STATEMENT_EXECUTE,
    RESULT_NEXT,
    RESULT_READ,
    RESULT_CLOSE
  }

  private static final class MixedLatencyBreakdown {
    private final long[] writeLatencies;
    private final long[] pointLookupLatencies;
    private final long[] rangeCountLatencies;
    private final long[] commitLatencies;
    private final AtomicInteger writeCount = new AtomicInteger();
    private final AtomicInteger pointLookupCount = new AtomicInteger();
    private final AtomicInteger rangeCountCount = new AtomicInteger();
    private final AtomicInteger commitCount = new AtomicInteger();

    private MixedLatencyBreakdown(int operations) {
      this.writeLatencies = new long[operations];
      this.pointLookupLatencies = new long[operations];
      this.rangeCountLatencies = new long[operations];
      this.commitLatencies = new long[operations];
    }

    private void record(BenchmarkOperationKind operationKind,
        long latencyMicros) {
      if (operationKind == BenchmarkOperationKind.WRITE) {
        record(writeLatencies, writeCount, latencyMicros);
      } else if (operationKind == BenchmarkOperationKind.POINT_LOOKUP) {
        record(pointLookupLatencies, pointLookupCount, latencyMicros);
      } else if (operationKind == BenchmarkOperationKind.RANGE_COUNT) {
        record(rangeCountLatencies, rangeCountCount, latencyMicros);
      } else if (operationKind == BenchmarkOperationKind.COMMIT) {
        record(commitLatencies, commitCount, latencyMicros);
      }
    }

    private void addDetails(Map<String, String> details) {
      addLatencyDetails(details, "mixedLatency.write", writeLatencies,
          writeCount.get());
      addLatencyDetails(details, "mixedLatency.pointLookup",
          pointLookupLatencies, pointLookupCount.get());
      addLatencyDetails(details, "mixedLatency.rangeCount",
          rangeCountLatencies, rangeCountCount.get());
      addLatencyDetails(details, "mixedLatency.commit",
          commitLatencies, commitCount.get());
    }

    private static void record(long[] latencies, AtomicInteger count,
        long latencyMicros) {
      int index = count.getAndIncrement();
      if (index < latencies.length) {
        latencies[index] = latencyMicros;
      }
    }

    private static void addLatencyDetails(Map<String, String> details,
        String prefix, long[] latencies, int count) {
      int safeCount = Math.max(0, Math.min(count, latencies.length));
      details.put(prefix + ".count", String.valueOf(safeCount));
      if (safeCount == 0) {
        details.put(prefix + ".avgLatencyMicros", "0");
        details.put(prefix + ".p50LatencyMicros", "0");
        details.put(prefix + ".p95LatencyMicros", "0");
        details.put(prefix + ".p99LatencyMicros", "0");
        details.put(prefix + ".maxLatencyMicros", "0");
        return;
      }
      long[] copy = Arrays.copyOf(latencies, safeCount);
      Arrays.sort(copy);
      long total = 0L;
      for (long latency : copy) {
        total += latency;
      }
      details.put(prefix + ".avgLatencyMicros",
          String.valueOf(total / safeCount));
      details.put(prefix + ".p50LatencyMicros",
          String.valueOf(percentile(copy, 0.50D)));
      details.put(prefix + ".p95LatencyMicros",
          String.valueOf(percentile(copy, 0.95D)));
      details.put(prefix + ".p99LatencyMicros",
          String.valueOf(percentile(copy, 0.99D)));
      details.put(prefix + ".maxLatencyMicros",
          String.valueOf(copy[copy.length - 1]));
    }
  }

  private static final class MixedAllocationBreakdown {
    private final AllocationCounter write = new AllocationCounter();
    private final AllocationCounter pointLookup = new AllocationCounter();
    private final AllocationCounter rangeCount = new AllocationCounter();
    private final AllocationCounter commit = new AllocationCounter();
    private final AllocationCounter parameterSet = new AllocationCounter();
    private final AllocationCounter statementExecute = new AllocationCounter();
    private final AllocationCounter resultNext = new AllocationCounter();
    private final AllocationCounter resultRead = new AllocationCounter();
    private final AllocationCounter resultClose = new AllocationCounter();
    private final Map<String, AllocationCounter> planStages =
        new ConcurrentHashMap<>();

    private void record(BenchmarkOperationKind operationKind,
        long allocatedBytes) {
      if (operationKind == BenchmarkOperationKind.WRITE) {
        write.record(allocatedBytes);
      } else if (operationKind == BenchmarkOperationKind.POINT_LOOKUP) {
        pointLookup.record(allocatedBytes);
      } else if (operationKind == BenchmarkOperationKind.RANGE_COUNT) {
        rangeCount.record(allocatedBytes);
      } else if (operationKind == BenchmarkOperationKind.COMMIT) {
        commit.record(allocatedBytes);
      }
    }

    private void recordStage(BenchmarkAllocationStage stage,
        long allocatedBytes) {
      recordStage(stage.name(), allocatedBytes);
    }

    private void recordStage(String stage, long allocatedBytes) {
      if (BenchmarkAllocationStage.PARAMETER_SET.name().equals(stage)) {
        parameterSet.record(allocatedBytes);
      } else if (BenchmarkAllocationStage.STATEMENT_EXECUTE.name()
          .equals(stage)) {
        statementExecute.record(allocatedBytes);
      } else if (BenchmarkAllocationStage.RESULT_NEXT.name().equals(stage)) {
        resultNext.record(allocatedBytes);
      } else if (BenchmarkAllocationStage.RESULT_READ.name().equals(stage)) {
        resultRead.record(allocatedBytes);
      } else if (BenchmarkAllocationStage.RESULT_CLOSE.name().equals(stage)) {
        resultClose.record(allocatedBytes);
      } else if (stage != null && stage.startsWith("plan.")) {
        planStages.computeIfAbsent(stage, ignored -> new AllocationCounter())
            .record(allocatedBytes);
      }
    }

    private void addDetails(Map<String, String> details) {
      write.addDetails(details, "mixedAllocation.write");
      pointLookup.addDetails(details, "mixedAllocation.pointLookup");
      rangeCount.addDetails(details, "mixedAllocation.rangeCount");
      commit.addDetails(details, "mixedAllocation.commit");
      parameterSet.addDetails(details, "mixedAllocation.jdbc.parameterSet");
      statementExecute.addDetails(details,
          "mixedAllocation.jdbc.statementExecute");
      resultNext.addDetails(details, "mixedAllocation.jdbc.resultNext");
      resultRead.addDetails(details, "mixedAllocation.jdbc.resultRead");
      resultClose.addDetails(details, "mixedAllocation.jdbc.resultClose");
      for (Map.Entry<String, AllocationCounter> entry : planStages.entrySet()) {
        entry.getValue().addDetails(details,
            "mixedAllocation." + entry.getKey());
      }
    }
  }

  private static final class AllocationCounter {
    private final AtomicInteger count = new AtomicInteger();
    private final AtomicLong totalBytes = new AtomicLong();

    private void record(long allocatedBytes) {
      count.incrementAndGet();
      totalBytes.addAndGet(Math.max(0L, allocatedBytes));
    }

    private void addDetails(Map<String, String> details, String prefix) {
      int safeCount = count.get();
      long safeTotalBytes = totalBytes.get();
      details.put(prefix + ".count", String.valueOf(safeCount));
      details.put(prefix + ".totalBytes", String.valueOf(safeTotalBytes));
      details.put(prefix + ".bytesPerOperation",
          String.valueOf(safeTotalBytes / Math.max(1, safeCount)));
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

  private static int defaultStatementBatchSize(String workload,
      int transactionBatchSize) {
    return "insert".equals(workload) && transactionBatchSize > 1
        ? transactionBatchSize : 1;
  }

  private static String tableEngine(Map<String, String> values) {
    return normalizeTableEngine(value(values, "tableEngine",
        TABLE_ENGINE_ADB));
  }

  private static String normalizeTableEngine(String tableEngine) {
    String value = tableEngine == null ? TABLE_ENGINE_ADB
        : tableEngine.trim().toLowerCase();
    if (TABLE_ENGINE_ADB.equals(value) || TABLE_ENGINE_H2.equals(value)) {
      return value;
    }
    throw new IllegalArgumentException("Unsupported tableEngine: "
        + tableEngine);
  }

  private static int statementBatchSize(Map<String, String> values,
      String workload, int transactionBatchSize) {
    int configured = nonNegativeInt(values, "statementBatchSize", 0);
    if (configured > 0) {
      return configured;
    }
    return defaultStatementBatchSize(workload, transactionBatchSize);
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
    private final Connection connection;
    private final PreparedStatement insert;
    private final PreparedStatement pointLookup;
    private final PreparedStatement pointLookupAll;
    private final PreparedStatement tableCount;
    private final PreparedStatement rangeScan;
    private final Statement primaryFind;
    private final Map<Integer, PreparedStatement> multiInserts =
        new HashMap<>();

    private BenchmarkStatements(Connection connection) throws Exception {
      this.connection = connection;
      this.insert = connection.prepareStatement("INSERT INTO " + TABLE_NAME
          + "(ID, NAME) VALUES (?, ?)");
      this.pointLookup = connection.prepareStatement("SELECT NAME FROM "
          + TABLE_NAME + " WHERE ID = ?");
      this.pointLookupAll = connection.prepareStatement("SELECT * FROM "
          + TABLE_NAME + " WHERE ID = ?");
      this.primaryFind = connection.createStatement();
      this.tableCount = connection.prepareStatement("SELECT COUNT(*) FROM "
          + TABLE_NAME);
      this.rangeScan = connection.prepareStatement("SELECT COUNT(*) FROM "
          + TABLE_NAME + " WHERE ID BETWEEN ? AND ?");
    }

    private void insert(long id, String name) throws Exception {
      long parameterStart = currentThreadAllocatedBytes();
      insert.setLong(1, id);
      insert.setString(2, name);
      recordCurrentMixedStage(BenchmarkAllocationStage.PARAMETER_SET,
          parameterStart, currentThreadAllocatedBytes());
      long executeStart = currentThreadAllocatedBytes();
      insert.executeUpdate();
      recordCurrentMixedStage(BenchmarkAllocationStage.STATEMENT_EXECUTE,
          executeStart, currentThreadAllocatedBytes());
    }

    private void insertMany(long firstId, int batchSize, String namePrefix)
        throws Exception {
      PreparedStatement statement = multiInsert(batchSize);
      int parameter = 1;
      for (int i = 0; i < batchSize; i++) {
        long id = firstId + i;
        statement.setLong(parameter++, id);
        statement.setString(parameter++, namePrefix + id);
      }
      statement.executeUpdate();
    }

    private PreparedStatement multiInsert(int batchSize) throws Exception {
      PreparedStatement statement = multiInserts.get(batchSize);
      if (statement != null) {
        return statement;
      }
      StringBuilder sql = new StringBuilder("INSERT INTO ");
      sql.append(TABLE_NAME).append("(ID, NAME) VALUES ");
      for (int i = 0; i < batchSize; i++) {
        if (i > 0) {
          sql.append(", ");
        }
        sql.append("(?, ?)");
      }
      statement = connection.prepareStatement(sql.toString());
      multiInserts.put(batchSize, statement);
      return statement;
    }

    private void pointLookup(long id) throws Exception {
      long parameterStart = currentThreadAllocatedBytes();
      pointLookup.setLong(1, id);
      recordCurrentMixedStage(BenchmarkAllocationStage.PARAMETER_SET,
          parameterStart, currentThreadAllocatedBytes());
      ResultSet resultSet = null;
      try {
        long executeStart = currentThreadAllocatedBytes();
        resultSet = pointLookup.executeQuery();
        recordCurrentMixedStage(BenchmarkAllocationStage.STATEMENT_EXECUTE,
            executeStart, currentThreadAllocatedBytes());
        while (true) {
          long nextStart = currentThreadAllocatedBytes();
          boolean hasNext = resultSet.next();
          recordCurrentMixedStage(BenchmarkAllocationStage.RESULT_NEXT,
              nextStart, currentThreadAllocatedBytes());
          if (!hasNext) {
            break;
          }
          long readStart = currentThreadAllocatedBytes();
          resultSet.getString(1);
          recordCurrentMixedStage(BenchmarkAllocationStage.RESULT_READ,
              readStart, currentThreadAllocatedBytes());
        }
      } finally {
        if (resultSet != null) {
          long closeStart = currentThreadAllocatedBytes();
          resultSet.close();
          recordCurrentMixedStage(BenchmarkAllocationStage.RESULT_CLOSE,
              closeStart, currentThreadAllocatedBytes());
        }
      }
    }

    private void pointLookupAll(long id) throws Exception {
      pointLookupAll.setLong(1, id);
      try (ResultSet ignored = pointLookupAll.executeQuery()) {
        while (ignored.next()) {
          ignored.getLong(1);
          ignored.getString(2);
        }
      }
    }

    private void primaryFind(long id) throws Exception {
      try (ResultSet ignored = primaryFind.executeQuery("SELECT NAME FROM "
          + TABLE_NAME + " WHERE ID = " + id)) {
        while (ignored.next()) {
          ignored.getString(1);
        }
      }
    }

    private void tableCount() throws Exception {
      try (ResultSet ignored = tableCount.executeQuery()) {
        while (ignored.next()) {
          ignored.getLong(1);
        }
      }
    }

    private void rangeScan(long start, long end) throws Exception {
      long parameterStart = currentThreadAllocatedBytes();
      rangeScan.setLong(1, start);
      rangeScan.setLong(2, end);
      recordCurrentMixedStage(BenchmarkAllocationStage.PARAMETER_SET,
          parameterStart, currentThreadAllocatedBytes());
      ResultSet resultSet = null;
      try {
        long executeStart = currentThreadAllocatedBytes();
        resultSet = rangeScan.executeQuery();
        recordCurrentMixedStage(BenchmarkAllocationStage.STATEMENT_EXECUTE,
            executeStart, currentThreadAllocatedBytes());
        while (true) {
          long nextStart = currentThreadAllocatedBytes();
          boolean hasNext = resultSet.next();
          recordCurrentMixedStage(BenchmarkAllocationStage.RESULT_NEXT,
              nextStart, currentThreadAllocatedBytes());
          if (!hasNext) {
            break;
          }
          long readStart = currentThreadAllocatedBytes();
          resultSet.getLong(1);
          recordCurrentMixedStage(BenchmarkAllocationStage.RESULT_READ,
              readStart, currentThreadAllocatedBytes());
        }
      } finally {
        if (resultSet != null) {
          long closeStart = currentThreadAllocatedBytes();
          resultSet.close();
          recordCurrentMixedStage(BenchmarkAllocationStage.RESULT_CLOSE,
              closeStart, currentThreadAllocatedBytes());
        }
      }
    }

    private void rangeCountWithLocalWrite(long id, String name)
        throws Exception {
      insert(id, name);
      rangeScan(id, id);
    }

    @Override
    public void close() throws Exception {
      for (PreparedStatement statement : multiInserts.values()) {
        statement.close();
      }
      rangeScan.close();
      tableCount.close();
      primaryFind.close();
      pointLookupAll.close();
      pointLookup.close();
      insert.close();
    }
  }
}
