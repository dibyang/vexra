package net.xdob.vexra.adb;

import net.xdob.vexra.adb.db.ScanDirection;
import net.xdob.vexra.adb.db.RowCodec;
import net.xdob.vexra.adb.db.RowValue;
import net.xdob.vexra.adb.db.AdbTable;
import net.xdob.vexra.adb.db.Transaction2;
import net.xdob.vexra.adb.db.TxnManager;
import net.xdob.vexra.adb.db.VersionScanSource;
import net.xdob.vexra.adb.db.AdbSqlDiagnosticSnapshot;
import net.xdob.vexra.adb.db.AdbSqlDiagnosticsRegistry;
import net.xdob.vexra.adb.db.AdbSqlOperationStats;
import net.xdob.vexra.adb.h2plugin.AdbJdbcUrlPrefixProvider;
import net.xdob.vexra.adb.jdbc.AdbDriver;
import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.adb.ldb.LdbStore;
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
    int statementBatchSize = statementBatchSize(values, workload,
        transactionBatchSize);
    boolean dropTable = bool(values, "dropTable", true);
    boolean sqlDiagnostics = bool(values, "sqlDiagnostics", true);
    Path output = Paths.get(value(values, "output",
        "build/adb-benchmark/adb-benchmark.properties"));

    AdbBenchmarkMain benchmark = new AdbBenchmarkMain();
    AdbBenchmarkResult result;
    if ("jdbc".equals(mode)) {
      result = benchmark.executeJdbc(url, workload, rows, warmupOperations,
          operations, rangeSize, dropTable, transactionBatchSize,
          statementBatchSize, sqlDiagnostics);
    } else if ("jdbc_bulk".equals(mode)) {
      result = benchmark.executeJdbcBulk(url, workload, rows,
          warmupOperations, operations, dropTable, transactionBatchSize,
          statementBatchSize, sqlDiagnostics);
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
   * @param workload workload 名称：`insert`、`point_lookup`、`point_lookup_all`、`range_scan` 或 `mixed`
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
      boolean sqlDiagnostics)
      throws Exception {
    requireSupportedWorkload(workload);
    loadJdbcDriver(url);
    try (Connection connection = DriverManager.getConnection(url,
        new Properties())) {
      connection.setAutoCommit(transactionBatchSize <= 1);
      prepareSchema(connection, rows, dropTable, sqlDiagnostics);
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
        long failed = 0;
        int pendingBatchOperations = 0;
        long started = System.nanoTime();
        if ("insert".equals(workload) && statementBatchSize > 1) {
          failed = executeInsertBatches(connection, statements, rows,
              operations, statementBatchSize, transactionBatchSize, true,
              latencies);
        } else {
          for (int i = 0; i < operations; i++) {
            long opStarted = System.nanoTime();
            try {
              executeOperation(statements, workload, rows, rangeSize, i, true,
                  statementBatchSize);
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
        }
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
      boolean sqlDiagnostics) throws Exception {
    if (!"insert".equals(workload)) {
      throw new IllegalArgumentException(
          "jdbc_bulk mode only supports insert workload: " + workload);
    }
    loadJdbcDriver(url);
    try (Connection connection = DriverManager.getConnection(url,
        new Properties())) {
      connection.setAutoCommit(false);
      prepareSchema(connection, rows, dropTable, sqlDiagnostics);
      connection.commit();
      AdbTable table = adbBenchmarkTable(connection);

      executeJdbcBulkBatches(connection, table, rows, warmupOperations,
          statementBatchSize, transactionBatchSize, false, null);
      AdbSqlDiagnosticsRegistry.resetAll();

      long[] latencies = new long[operations];
      long started = System.nanoTime();
      long failed = executeJdbcBulkBatches(connection, table, rows, operations,
          statementBatchSize, transactionBatchSize, true, latencies);
      long durationMillis = Math.max(1L,
          (System.nanoTime() - started) / 1_000_000L);
      Arrays.sort(latencies);
      double throughput = operations * 1000D / durationMillis;
      return new AdbBenchmarkResult("jdbc_bulk", workload, url,
          warmupOperations, operations, failed, durationMillis, throughput,
          percentile(latencies, 0.50D), percentile(latencies, 0.95D),
          percentile(latencies, 0.99D), latencies[latencies.length - 1],
          collectSqlDiagnostics());
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
      long started = System.nanoTime();
      long failed = executeTxnInserts(txnManager, tableId, rows, operations,
          transactionBatchSize, true, latencies);
      long durationMillis = Math.max(1L,
          (System.nanoTime() - started) / 1_000_000L);
      Arrays.sort(latencies);
      double throughput = operations * 1000D / durationMillis;
      return new AdbBenchmarkResult("txn", workload, storeDir,
          warmupOperations, operations, failed, durationMillis, throughput,
          percentile(latencies, 0.50D), percentile(latencies, 0.95D),
          percentile(latencies, 0.99D), latencies[latencies.length - 1]);
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
      boolean dropTable, boolean sqlDiagnostics) throws Exception {
    try (Statement statement = connection.createStatement()) {
      if (dropTable) {
        statement.execute("DROP TABLE IF EXISTS " + TABLE_NAME);
      }
      String diagnosticsParam = sqlDiagnostics ? ""
          : " WITH \"adb.sql.diagnostics=false\"";
      statement.execute("CREATE TABLE IF NOT EXISTS " + TABLE_NAME
          + "(ID BIGINT PRIMARY KEY, NAME VARCHAR) ENGINE \"adb_table\""
          + diagnosticsParam);
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
    } else if ("point_lookup_all".equals(workload)) {
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
        && !"point_lookup_all".equals(workload)
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

  private static int defaultStatementBatchSize(String workload,
      int transactionBatchSize) {
    return "insert".equals(workload) && transactionBatchSize > 1
        ? transactionBatchSize : 1;
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
    private final PreparedStatement rangeScan;
    private final Map<Integer, PreparedStatement> multiInserts =
        new HashMap<>();

    private BenchmarkStatements(Connection connection) throws Exception {
      this.connection = connection;
      this.insert = connection.prepareStatement("MERGE INTO " + TABLE_NAME
          + "(ID, NAME) VALUES (?, ?)");
      this.pointLookup = connection.prepareStatement("SELECT NAME FROM "
          + TABLE_NAME + " WHERE ID = ?");
      this.pointLookupAll = connection.prepareStatement("SELECT * FROM "
          + TABLE_NAME + " WHERE ID = ?");
      this.rangeScan = connection.prepareStatement("SELECT COUNT(*) FROM "
          + TABLE_NAME + " WHERE ID BETWEEN ? AND ?");
    }

    private void insert(long id, String name) throws Exception {
      insert.setLong(1, id);
      insert.setString(2, name);
      insert.executeUpdate();
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
      pointLookup.setLong(1, id);
      try (ResultSet ignored = pointLookup.executeQuery()) {
        while (ignored.next()) {
          ignored.getString(1);
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
      for (PreparedStatement statement : multiInserts.values()) {
        statement.close();
      }
      rangeScan.close();
      pointLookupAll.close();
      pointLookup.close();
      insert.close();
    }
  }
}
