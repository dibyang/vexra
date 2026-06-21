# ADB Performance Baseline Report

## Background

The current ADB benchmark numbers looked unexpectedly low, so this report
separates `vexra-ldb` local-store cost from the full
`JDBC -> h2db -> ADB table engine -> MVCC / lock / index / commitTs -> LdbStore`
SQL execution path.

This report records the local short-run baseline from 2026-06-21. It covers only
file-backed `ldb`; `mem` mode is intentionally out of scope.

## Test Environment

| Item | Value |
| --- | --- |
| Date | 2026-06-21 |
| ADB module | `vexra-adb` |
| ldb version | `0.10.0` |
| Rows | 5000 |
| Warmup operations | 300 |
| Measured operations | 3000 |
| Range size | 32 |
| Concurrency | Single thread |
| Persistence mode | `jdbc:adb:ldb:*` or local `LdbStore` |

## Benchmark Tool

`AdbBenchmarkMain` supports two modes:

| Mode | Path | Purpose |
| --- | --- | --- |
| `jdbc` | `JDBC -> h2db -> ADB table engine -> LdbStore` | Measures the real SQL/JDBC path |
| `txn` | `AdbBenchmarkMain -> TxnManager -> MVCC RowCodec -> LdbStore` | Measures ADB local transaction/MVCC insert capacity without SQL parser/table-engine cost |
| `store` | `AdbBenchmarkMain -> LdbStore` | Measures the local store wrapper baseline without SQL/table-engine cost |

`jdbc` mode also supports `--transactionBatchSize` to compare one-statement
auto-commit with batched transaction behavior.

## Results

| Mode | Workload | transactionBatchSize | Throughput ops/s | p50 us | p95 us | p99 us | max us | Result file |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `jdbc` | `insert` | 1 | 83.80 | 11615 | 16909 | 19506 | 69744 | `vexra-adb/build/adb-benchmark/insert.properties` |
| `jdbc` | `point_lookup` | 1 | 228.80 | 4314 | 5790 | 6911 | 11724 | `vexra-adb/build/adb-benchmark/point_lookup.properties` |
| `jdbc` | `range_scan` | 1 | 72.80 | 13308 | 23659 | 27661 | 39264 | `vexra-adb/build/adb-benchmark/range_scan.properties` |
| `jdbc` | `mixed` | 1 | 154.35 | 4376 | 19425 | 24190 | 43916 | `vexra-adb/build/adb-benchmark/mixed.properties` |
| `jdbc` | `insert` | 100 | 189.12 | 4245 | 8380 | 15551 | 102062 | `vexra-adb/build/adb-benchmark/jdbc_insert_batch100.properties` |
| `jdbc` | `mixed` | 100 | 470.15 | 699 | 8619 | 11639 | 19240 | `vexra-adb/build/adb-benchmark/jdbc_mixed_batch100.properties` |
| `jdbc` | `insert` | 100, optimized | 242.99 | 3135 | 5829 | 9776 | 123811 | `vexra-adb/build/adb-benchmark/jdbc_insert_batch100_opt1.properties` |
| `jdbc` | `mixed` | 100, optimized | 500.08 | 702 | 7923 | 12301 | 23370 | `vexra-adb/build/adb-benchmark/jdbc_mixed_batch100_opt1.properties` |
| `store` | `insert` | N/A | 130434.78 | 5 | 18 | 41 | 706 | `vexra-adb/build/adb-benchmark/store_insert.properties` |
| `store` | `point_lookup` | N/A | 200000.00 | 3 | 9 | 28 | 388 | `vexra-adb/build/adb-benchmark/store_point_lookup.properties` |
| `store` | `range_scan` | N/A | 2439.02 | 322 | 772 | 1480 | 7223 | `vexra-adb/build/adb-benchmark/store_range_scan.properties` |
| `store` | `mixed` | N/A | 13392.86 | 2 | 352 | 612 | 4615 | `vexra-adb/build/adb-benchmark/store_mixed.properties` |

## Conclusions

1. The data does not support the conclusion that the ldb local store itself is
   slow. `store` mode reaches about 130k insert ops/s and 200k point-lookups/s.
2. The current low throughput mainly comes from the SQL/JDBC/table-engine path.
   Even with JDBC batch size 100, insert throughput only improves from about
   84 ops/s to about 189 ops/s.
3. `mixed` improves from about 154 ops/s to about 470 ops/s with batch size
   100, so commit frequency matters but is not the only bottleneck.
4. The next optimization round should profile ADB table-engine per-row cost,
   MVCC/index update cost, transaction timestamp handling, and lock paths before
   optimizing ldb.

## First Optimization Result

The first optimization made two low-risk changes:

1. The benchmark now exports SQL/table-engine diagnostic aggregates as
   `sqlDiagnostics.*` properties.
2. `TxnMap2.put/putIfAbsent/delete` reuses the old visible version already read
   by the table/index layer, avoiding an extra version scan in
   `Transaction2.put/delete`.

Before/after comparison:

| Workload | Before throughput ops/s | After throughput ops/s | Change |
| --- | ---: | ---: | ---: |
| `insert` batch 100 | 189.12 | 242.99 | +28.5% |
| `mixed` batch 100 | 470.15 | 500.08 | +6.4% |

The new `sqlDiagnostics.operationStats.*` fields show the main measured
table-engine operations in the `mixed` batch-100 window:

| Operation | Count | Avg latency us | Total latency ms |
| --- | ---: | ---: | ---: |
| `ADB_TABLE_PRIMARY_FIND ADB_BENCH` | 3000 | 699 | 2098 |
| `ADB_TABLE_ADD_ROW ADB_BENCH` | 300 | 2213 | 664 |

This confirms that removing the duplicate scan helps writes, but SQL/JDBC, H2
execution, commit txn-ref scan, and table-engine boundaries remain the main
bottlenecks.

## Second Insert Optimization Result

The second round focused on the insert hot path:

1. Local single-node transactions skip per-row durable intents when no region
   commit coordinator is installed. Commit writes committed versions and meta in
   one lower-level write batch.
2. Append-only primary-key inserts maintain an in-process max rowId hint. When
   the new rowId is greater than the known committed high-water mark and the
   current transaction has not written the same key, the primary-key committed
   uniqueness scan is skipped.
3. The same append fast path skips the row-lock HashMap/wait path. Random
   inserts, duplicate keys, updates, deletes, and distributed commits keep the
   full lock and validation path.
4. The benchmark now supports multi-values insert, `statementBatchSize`, `txn`
   mode, and the table parameter `adb.sql.diagnostics=false`.

Current reproducible results:

| Mode | Workload | Batch | Throughput ops/s | p99 us | Result file | Notes |
| --- | --- | ---: | ---: | ---: | --- | --- |
| `jdbc` | `insert` | 3000 | 2752.29 | 363 | `vexra-adb/build/adb-benchmark/jdbc_insert_goal_fastpath_reuse.properties` | Best short-run SQL/JDBC/table-engine result so far; still below 3000 |
| `txn` | `insert` | 3000 | 63829.79 | 25 | `vexra-adb/build/adb-benchmark/txn_insert_goal.properties` | ADB local transaction/MVCC/commit path; above the 3000 ops/s target |

Conclusion: ADB local transaction insert capacity is now above `3000 ops/s`.
There is no current evidence that ldb or the ADB MVCC/commit path is the main
insert bottleneck. The remaining gap is concentrated at the
`JDBC -> h2db SQL parser/executor -> TableEngine.addRow` row-by-row boundary. If
JDBC insert must also stay above `3000 ops/s`, the next phase should add a real
SQL bulk insert entry point instead of only optimizing the lower store layer.

Transaction-layer insert reproduction command:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=txn" `
  "-PadbBenchmarkStoreDir=D:/work/java2/vexra/vexra-adb/build/adb-benchmark/store/goal-txn-insert" `
  "-PadbBenchmarkWorkload=insert" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkWarmupOperations=300" `
  "-PadbBenchmarkOperations=3000" `
  "-PadbBenchmarkTransactionBatchSize=3000" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/txn_insert_goal.properties"
```

## Round 7 Ordinary JDBC Insert Auto-Bulk Result

This round adds `net.xdob.vexra.adb.jdbc.AdbDriver` as a lightweight
compatibility Driver for `jdbc:adb:*`. The real connection, general SQL
execution, and non-matching statements are still delegated to h2db. When a
caller uses `DriverManager` with a parameterized multi-values
`PreparedStatement`:

```sql
INSERT INTO TEST(ID, NAME) VALUES (?, ?), (?, ?), ...
```

or a simple literal multi-values `Statement`:

```sql
INSERT INTO TEST(ID, NAME) VALUES (1, 'a'), (2, 'b'), ...
```

and the target table is an `AdbTable`, the wrapper converts parameters or
literals into H2 `Row` objects and calls `AdbTable.bulkInsertAppendRows`. It
also preserves JDBC auto-commit behavior by committing after a successful bulk
write when `autoCommit=true`. Unsupported SQL forms, non-ADB tables,
incomplete parameters, single-row inserts, and literal expressions continue to
use the original h2db path.

Measured results:

| Mode | Workload | Batch | Diagnostics | Throughput ops/s | p99 us | Result file | Notes |
| --- | --- | ---: | --- | ---: | ---: | --- | --- |
| `jdbc` | `insert` | 1000 | on | 43478.26 | 23 | `vexra-adb/build/adb-benchmark/jdbc_insert_driver_bulk_diag_r2.properties` | Diagnostics confirm a single `ADB_TABLE_BULK_ADD_ROW ADB_BENCH` operation |
| `jdbc` | `insert` | 3000 | off | 76923.08 | 13 | `vexra-adb/build/adb-benchmark/jdbc_insert_driver_bulk_no_diag_r2.properties` | Ordinary JDBC SQL now auto-routes to the bulk path and exceeds both 3000 and 5000 ops/s |
| `jdbc` | `mixed` | 100 | on | 1779.36 | 2093 | `vexra-adb/build/adb-benchmark/jdbc_mixed_driver_bulk.properties` | Mixed regression; previous comparable result was about 1697.79 ops/s |
| `jdbc` | `insert` | 3000 | off | 73170.73 | 13 | `vexra-adb/build/adb-benchmark/jdbc_insert_driver_bulk_literal_stage.properties` | Insert regression after adding literal Statement support |
| `jdbc` | `mixed` | 100 | on | 1718.21 | 2027 | `vexra-adb/build/adb-benchmark/jdbc_mixed_driver_bulk_literal_stage.properties` | Mixed regression after adding literal Statement support |

The new integration tests `preparedMultiValuesInsertUsesAdbDriverBulkPath`,
`statementLiteralMultiValuesInsertUsesAdbDriverBulkPath`, and
`unsupportedStatementInsertFallsBackToH2Path` cover ordinary
`DriverManager + jdbc:adb:* + PreparedStatement/Statement` usage. They assert
that supported multi-values INSERT statements produce `ADB_TABLE_BULK_ADD_ROW
TEST`, while expression literals fall back to h2db's row-by-row path.

JDBC insert auto-bulk reproduction command:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=jdbc" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/insert-driver-bulk-literal-stage/adb-benchmark;DB_CLOSE_DELAY=0" `
  "-PadbBenchmarkWorkload=insert" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkWarmupOperations=300" `
  "-PadbBenchmarkOperations=3000" `
  "-PadbBenchmarkTransactionBatchSize=3000" `
  "-PadbBenchmarkStatementBatchSize=3000" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_insert_driver_bulk_literal_stage.properties" `
  "-PadbBenchmarkSqlDiagnostics=false"
```

Remaining limitations: the automatic bulk path currently covers parameterized
multi-values `PreparedStatement` SQL and simple literal multi-values
`Statement` SQL. It does not yet cover `INSERT ... SELECT`, `DEFAULT VALUES`,
literal expressions/functions, `ON DUPLICATE KEY`, `RETURNING`, or the full
trigger and delta-table semantics available inside h2db's native `Insert`
executor. A
future h2db table-level bulk callback is still the cleaner path to full
transparency; ADB's `bulkInsertAppendRows` can remain the implementation target.

## Round 8 JDBC Primary-Key Point-Lookup Fast Path

This round adds a parameterized primary-key lookup fast path in the
`jdbc:adb:*` compatibility Driver. It recognizes the narrow SQL shape:

```sql
SELECT col[, ...] FROM table WHERE pk = ?
```

The target table must be an `AdbTable`, the `WHERE` column must be the table
primary-key column or ROWID, and projected expressions must be simple column
names. Matching statements bypass the generic h2db query executor and
`AdbPrimaryIndex.find`; the wrapper reads the visible `RowValue` through the
current session's `TxnMap2` and uses `RowCodec.decodeColumns` to decode only the
projected columns. Other SQL forms, non-primary-key predicates, projected
expressions, missing parameters, and non-ADB tables continue to use the original
h2db path.

`TxnManager` still validates by default that the physical committed version
exists before using a committed-row cache entry. This protects reads after a
restore from returning stale in-memory cache data. Pure local benchmarks can
explicitly skip the check with
`-Dvexra.adb.rowCache.trustCommitted=true` to measure the shortest point-read
path.

Measured results:

| Mode | Workload | Batch | Diagnostics | Throughput ops/s | p50 us | p95 us | p99 us | max us | Result file |
| --- | --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| `jdbc` | `point_lookup` | 1 | on | 2664.30 | 325 | 688 | 1065 | 2274 | `vexra-adb/build/adb-benchmark/point_lookup_driver_safe_stage.properties` |
| `jdbc` | `mixed` | 100 | on | 1623.38 | 477 | 1403 | 2230 | 7069 | `vexra-adb/build/adb-benchmark/jdbc_mixed_driver_point_safe_stage.properties` |

The new integration test
`preparedPrimaryKeyLookupUsesAdbDriverFastPath` covers
`DriverManager + jdbc:adb:* + PreparedStatement` primary-key lookup. It asserts
the returned value and verifies diagnostics contain
`ADB_TABLE_POINT_LOOKUP_FAST TEST` instead of `ADB_TABLE_PRIMARY_FIND TEST`.

Conclusion: ordinary JDBC primary-key lookup improved from the initial
228.80 ops/s and the previous SQL-path range of about 770-780 ops/s to about
2664.30 ops/s. The mixed workload did not improve further because it still
contains range/count work that records 900 `ADB_TABLE_PRIMARY_FIND ADB_BENCH`
operations, and transaction commit plus range-scan cost still compete for total
latency. The next highest-value optimization should target the SQL range/count
fast path or mixed-workload commit cost.

Point-lookup reproduction command:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=jdbc" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/point-driver-safe-stage/adb-benchmark;DB_CLOSE_DELAY=0" `
  "-PadbBenchmarkWorkload=point_lookup" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkWarmupOperations=300" `
  "-PadbBenchmarkOperations=3000" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/point_lookup_driver_safe_stage.properties"
```

Mixed regression command:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=jdbc" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/mixed-driver-point-safe-stage/adb-benchmark;DB_CLOSE_DELAY=0" `
  "-PadbBenchmarkWorkload=mixed" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkWarmupOperations=300" `
  "-PadbBenchmarkOperations=3000" `
  "-PadbBenchmarkTransactionBatchSize=100" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_driver_point_safe_stage.properties"
```

## Round 9 JDBC Range/Count Fast Path

This round adds a parameterized primary-key range COUNT fast path in the
`jdbc:adb:*` compatibility Driver. It recognizes the narrow SQL shape:

```sql
SELECT COUNT(*) FROM table WHERE pk BETWEEN ? AND ?
```

The target table must be an `AdbTable`, the `WHERE` column must be the table
primary-key column or ROWID, and the aggregate must be a simple `COUNT(*)`.
Matching statements bypass the generic h2db query executor and aggregate path.
The wrapper counts rows directly through the current session's
`TxnMap2.entryIterator` / `TableScanCursor`, preserving transaction visibility
without creating H2 `Row` objects for the COUNT. Non-primary-key ranges,
secondary-index ranges, expressions, aliases, and other SQL forms continue to
use the original h2db path.

Measured results:

| Mode | Workload | Batch | Diagnostics | Throughput ops/s | p50 us | p95 us | p99 us | max us | Result file |
| --- | --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| `jdbc` | `range_scan` | 1 | on | 1388.89 | 668 | 1312 | 1750 | 7718 | `vexra-adb/build/adb-benchmark/range_count_fast_stage.properties` |
| `jdbc` | `mixed` | 100 | on | 1651.07 | 474 | 1177 | 2002 | 7973 | `vexra-adb/build/adb-benchmark/jdbc_mixed_range_count_fast_stage.properties` |

The new integration tests
`preparedPrimaryKeyRangeCountUsesAdbDriverFastPath` and
`preparedNonPrimaryRangeCountFallsBackToH2Path` cover the primary-key BETWEEN
COUNT fast path and the non-primary range fallback boundary. Diagnostics show
that the pure range window records only `ADB_TABLE_RANGE_COUNT_FAST ADB_BENCH`;
in the mixed workload, `ADB_TABLE_RANGE_COUNT_FAST ADB_BENCH` is recorded 600
times, so the range/count portion of the benchmark is using the fast path.

Conclusion: pure `range_scan` improved from the previous SQL-path result of
about 551.98 ops/s to about 1388.89 ops/s, with p99 reduced from about 4613us to
1750us. `mixed` improved modestly from the previous safe-default result of about
1623.38 ops/s to about 1651.07 ops/s, with p99 reduced from about 2230us to
2002us. The mixed workload is now more likely bottlenecked by point-lookup
committed-cache validation, commit cost, and remaining h2db executor boundaries.

Range/count reproduction command:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=jdbc" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/range-count-fast-stage/adb-benchmark;DB_CLOSE_DELAY=0" `
  "-PadbBenchmarkWorkload=range_scan" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkWarmupOperations=300" `
  "-PadbBenchmarkOperations=3000" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/range_count_fast_stage.properties"
```

Mixed regression command:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=jdbc" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/mixed-range-count-fast-stage/adb-benchmark;DB_CLOSE_DELAY=0" `
  "-PadbBenchmarkWorkload=mixed" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkWarmupOperations=300" `
  "-PadbBenchmarkOperations=3000" `
  "-PadbBenchmarkTransactionBatchSize=100" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_range_count_fast_stage.properties"
```

## Round 10 JDBC `SELECT *` Point-Lookup Fast Path

This round extends the primary-key point-lookup fast path to the common
`SELECT * FROM table WHERE pk = ?` SQL shape. The previous fast path only
accepted explicit column lists such as `SELECT NAME FROM ...`, so ORM-style or
hand-written `SELECT *` point reads still fell back to h2db's generic query
executor. The wrapper now expands `*` from the resolved `AdbTable` column list,
keeps the same primary-key safety checks, and decodes the visible `RowValue`
directly into all table columns.

Measured result:

| Mode | Workload | Batch | Diagnostics | Throughput ops/s | p50 us | p95 us | p99 us | max us | Result file |
| --- | --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| `jdbc` | `point_lookup_all` | 1 | on | 1767.83 | 418 | 1160 | 1554 | 3146 | `vexra-adb/build/adb-benchmark/point_lookup_all_fast_stage.properties` |

Diagnostics record only `ADB_TABLE_POINT_LOOKUP_FAST ADB_BENCH` for the measured
window, confirming that `SELECT *` no longer uses `ADB_TABLE_PRIMARY_FIND` for
this narrow primary-key lookup form. The throughput is lower than the
single-column `point_lookup` result because the fast path returns and reads all
columns, but it removes the larger generic h2db executor boundary for a common
application SQL shape.

`SELECT *` point-lookup reproduction command:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=jdbc" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/point-lookup-all-fast-stage/adb-benchmark;DB_CLOSE_DELAY=0" `
  "-PadbBenchmarkWorkload=point_lookup_all" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkWarmupOperations=300" `
  "-PadbBenchmarkOperations=3000" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/point_lookup_all_fast_stage.properties"
```

## Round 11 JDBC Table COUNT Fast Path and Row-Count Cache

This round adds a fast path for the narrow SQL shape:

```sql
SELECT COUNT(*) FROM table
```

`PreparedStatement.executeQuery()` and `Statement.executeQuery(sql)` can now
return the count directly from ADB row-count metadata plus the current
transaction's local row-count delta. Other aggregate forms, `WHERE` predicates,
aliases, and expressions continue to use h2db's original execution path.

The first implementation only bypassed h2db aggregation and still read the
committed row-count base by scanning persisted row-count deltas. That measured
about 761.61 ops/s with p99 2785us, proving the next bottleneck was row-count
metadata resolution itself. This round therefore also adds a conservative
in-process committed row-count cache: the first lookup still loads from META,
successful commits update cached table counts by their durable row-count delta,
and truncate/table-epoch updates invalidate cached entries for that table. A
restart or restore starts with an empty cache and falls back to the existing
durable scan.

Measured result after the cache:

| Mode | Workload | Batch | Diagnostics | Throughput ops/s | p50 us | p95 us | p99 us | max us | Result file |
| --- | --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| `jdbc` | `table_count` | 1 | on | 2577.32 | 351 | 657 | 1115 | 2005 | `vexra-adb/build/adb-benchmark/table_count_cache_stage.properties` |

Diagnostics record `ADB_TABLE_TABLE_COUNT_FAST ADB_BENCH` for the measured
window. The integration test also verifies that the fast path sees uncommitted
local row-count delta and returns to the committed count after rollback.

Table-count reproduction command:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=jdbc" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/table-count-cache-stage/adb-benchmark;DB_CLOSE_DELAY=0" `
  "-PadbBenchmarkWorkload=table_count" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkWarmupOperations=300" `
  "-PadbBenchmarkOperations=3000" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/table_count_cache_stage.properties"
```

## Round 6 Ordinary JDBC Insert Micro-Optimization

This round does not claim that ordinary SQL already reaches the ADB bulk path.
With h2db 2.3.0, `Insert` still calls `Table.addRow(SessionLocal, Row)` row by
row and `Table` does not expose a table-level bulk insert callback. Therefore
this round only reduces ADB's own ordinary `Table.addRow` hot-path cost:

1. `TxnMap2` caches the `TabId` for the same table within the same transaction,
   reducing repeated epoch wrapping and object allocation before `RowKey`
   construction.
2. `TxnMap2` adds an in-transaction append high-water mark. After one row key
   has passed the conservative append uniqueness check, later larger row ids in
   the same transaction can skip the global rowId hint lookup and local write-set
   lookup. Out-of-order keys, duplicate keys, rollback/savepoint, and truncate
   still fall back to the conservative path.
3. `TxnManager` aggregates rowId hints by table after a successful commit, so a
   large insert batch updates `ConcurrentHashMap + AtomicLong` once per table
   instead of once per row.
4. A new integration test covers duplicate primary keys inside one ordinary
   multi-values insert under an explicit transaction, followed by rollback.

Current reproducible results:

| Mode | Workload | Batch | Diagnostics | Throughput ops/s | p99 us | Result file | Notes |
| --- | --- | ---: | --- | ---: | ---: | --- | --- |
| `jdbc` | `insert` | 3000 | off | 2245.51 | 445 | `vexra-adb/build/adb-benchmark/jdbc_insert_no_diag_current.properties` | Current-code baseline before this round, with diagnostics disabled |
| `jdbc` | `insert` | 3000 | off | 2631.58 | 380 | `vexra-adb/build/adb-benchmark/jdbc_insert_commit_hint_batch_no_diag_r2.properties` | After this round, isolated rerun |
| `jdbc` | `mixed` | 100 | on | 1697.79 | 2195 | `vexra-adb/build/adb-benchmark/jdbc_mixed_append_highwater.properties` | Mixed regression; previous comparable result was about 1538.46 ops/s |

Conclusion: ADB-side ordinary `addRow` micro-optimizations improve real SQL
insert and mixed workload behavior, but they do not yet stabilize ordinary JDBC
insert above `3000 ops/s`, and they are still far from the desired `5000 ops/s`
headroom. Completing "ordinary `INSERT INTO ... VALUES (...), (...)`
automatically hits the bulk path" still requires h2db to expose a table-level
bulk insert SPI in the `Insert` layer while preserving trigger, constraint,
generated column, delta table, and `ON DUPLICATE KEY` semantics. ADB's existing
`bulkInsertAppendRows` remains the target implementation for that SPI.

JDBC insert reproduction command with diagnostics disabled:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=jdbc" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/insert-commit-hint-batch-no-diag-r2/adb-benchmark;DB_CLOSE_DELAY=0" `
  "-PadbBenchmarkWorkload=insert" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkWarmupOperations=300" `
  "-PadbBenchmarkOperations=3000" `
  "-PadbBenchmarkTransactionBatchSize=3000" `
  "-PadbBenchmarkStatementBatchSize=3000" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_insert_commit_hint_batch_no_diag_r2.properties" `
  "-PadbBenchmarkSqlDiagnostics=false"
```

## Fifth Round: Range Scan / Count Optimization

This round optimizes table range-scan visibility resolution. The old
`TableScanCursor` was already positioned on the current logical row in the main
scan source, but still called `DefaultVisibleRowResolver`, which opened another
committed-version scan for the same row. `SELECT COUNT(*) ... WHERE ID BETWEEN
? AND ?` paid this extra scan once for every row in the range. The new cursor
resolves the visible version directly from the current `VersionScanSource`,
while preserving current-transaction write-set priority, `startTs` snapshot
visibility, deleted-row filtering, and rowId propagation.

Measured results:

| Mode | workload | batch | throughput ops/s | p50 us | p95 us | p99 us | max us | Result file |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `jdbc` | `range_scan` | 1 | 551.98 | 1470 | 3554 | 4613 | 8419 | `vexra-adb/build/adb-benchmark/range_scan_inline_visible.properties` |
| `jdbc` | `mixed` | 100 | 1538.46 | 453 | 1626 | 2686 | 7788 | `vexra-adb/build/adb-benchmark/jdbc_mixed_range_inline_visible.properties` |

Compared with the initial baseline, `range_scan` improved from about 72.80
ops/s to about 551.98 ops/s. Compared with the previous mixed result, `mixed`
batch 100 improved from about 981.68 ops/s to about 1538.46 ops/s. This
confirms that repeated per-row visibility scans were a major range/count
bottleneck, not the lower ldb store itself. Full
`.\gradlew.bat :vexra-adb:test --rerun-tasks` passed, with an added integration
test covering range COUNT visibility for a local delete and rollback.

Range reproduction command:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=jdbc" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/range-inline-visible/adb-benchmark;DB_CLOSE_DELAY=0" `
  "-PadbBenchmarkWorkload=range_scan" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkWarmupOperations=300" `
  "-PadbBenchmarkOperations=3000" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/range_scan_inline_visible.properties"
```

Mixed workload reproduction command:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=jdbc" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/mixed-range-inline-visible/adb-benchmark;DB_CLOSE_DELAY=0" `
  "-PadbBenchmarkWorkload=mixed" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkWarmupOperations=300" `
  "-PadbBenchmarkOperations=3000" `
  "-PadbBenchmarkTransactionBatchSize=100" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_range_inline_visible.properties"
```

## Third JDBC Bulk Insert Result

The third round adds a JDBC-connection bulk insert path. The benchmark still
opens the database through `jdbc:adb:ldb:*` and creates the ADB table through H2,
but the measured insert phase uses the current H2 `SessionLocal` to call the ADB
table bulk API directly. This avoids H2 SQL executor's row-by-row
`Table.addRow` dispatch while preserving the JDBC transaction boundary.

Current result lines:

| Mode | Workload | Operations | Batch | Throughput ops/s | p99 us | Result file | Notes |
| --- | --- | ---: | ---: | ---: | ---: | --- | --- |
| `store` | `insert` | 3000 | N/A | 130434.78 | 41 | `vexra-adb/build/adb-benchmark/store_insert.properties` | Local store wrapper baseline |
| `txn` | `insert` | 3000 | 3000 | 63829.79 | 25 | `vexra-adb/build/adb-benchmark/txn_insert_goal.properties` | ADB local transaction/MVCC/commit path |
| `jdbc_bulk` | `insert` | 100000 | 5000 | 357142.86 | 6 | `vexra-adb/build/adb-benchmark/jdbc_bulk_insert_goal_100k.properties` | JDBC connection plus ADB table bulk API |

The `jdbc_bulk` path is above both the hard target (`3000 ops/s`) and the desired
margin (`5000 ops/s`). The fast path remains local-only: tables with a region
commit coordinator are rejected rather than silently bypassing distributed
commit. Duplicate primary keys still raise an error.

This increment also supports local secondary-index tables in
`bulkInsertAppendRows`. The path validates primary keys first, then registers
secondary index keys in the same ADB transaction write set so they commit or
rollback with the user transaction. JUnit coverage now includes non-unique
secondary-index lookup, in-batch unique secondary conflict rejection, and
rollback leaving neither row nor index entries behind.

With `h2db:2.3.0`, `org.h2.command.dml.Insert` still calls
`Table.addRow(SessionLocal, Row)` once per `VALUES` row, and
`org.h2.table.Table` does not expose a table-level bulk insert callback.
Therefore ordinary user SQL such as `INSERT INTO ... VALUES (...), (...)`
cannot be routed automatically into `bulkInsertAppendRows` from the ADB plugin
alone. Finishing ordinary-SQL automatic bulk insert requires a new h2db
table-level bulk insert SPI that preserves trigger, constraint, generated
column, `ON DUPLICATE KEY`, and delta-table semantics. ADB keeps
`bulkInsertAppendRows` as the table entry point that such an SPI can call.

JDBC bulk insert reproduction command:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=jdbc_bulk" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/goal-jdbc-bulk-insert-100k/adb-benchmark" `
  "-PadbBenchmarkWorkload=insert" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkWarmupOperations=3000" `
  "-PadbBenchmarkOperations=100000" `
  "-PadbBenchmarkTransactionBatchSize=5000" `
  "-PadbBenchmarkStatementBatchSize=5000" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_bulk_insert_goal_100k.properties"
```

## Fifth Round: Multi-Thread Mixed Workload Diagnostics

This round adds the `threads` parameter to the `jdbc` benchmark path and writes
`concurrency.*` fields into the properties report. The run uses the same
file-backed `jdbc:adb:ldb:*` mixed workload with `rows=5000`,
`warmupOperations=300`, `operations=3000`, and `transactionBatchSize=100`.

| threads | throughput ops/s | p50 us | p95 us | p99 us | max us | per-thread ops/s | Result file |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 1351.35 | 578 | 1667 | 2656 | 7378 | 1351.35 | `vexra-adb/build/adb-benchmark/jdbc_mixed_threads_1.properties` |
| 2 | 1110.70 | 956 | 2153 | 3878 | 9316 | 555.35 | `vexra-adb/build/adb-benchmark/jdbc_mixed_threads_2.properties` |
| 4 | 1315.21 | 1458 | 3224 | 5816 | 10465 | 328.80 | `vexra-adb/build/adb-benchmark/jdbc_mixed_threads_4.properties` |
| 8 | 1515.15 | 2338 | 5159 | 8046 | 19585 | 189.39 | `vexra-adb/build/adb-benchmark/jdbc_mixed_threads_8.properties` |

Conclusion: the mixed workload improves by only about 12.1% from 1 thread to 8
threads, while p99 grows from 2656us to 8046us. More client-side concurrency
mainly amplifies shared-path latency instead of delivering linear throughput.
The `sqlDiagnostics.*` output also shows that average latency for
`ADB_TABLE_ADD_ROW`, `ADB_TABLE_PRIMARY_FIND`, and `ADB_TABLE_RANGE_COUNT_FAST`
rises with concurrency; at 8 threads, `ADB_TABLE_ADD_ROW` reaches a 64ms max
latency. The next optimization stage should split commit / row-count /
primary-key lookup / range-count / store-write / lock-wait timing instead of
only increasing client threads.

Reproduction example:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=jdbc" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/mixed-threads-8/adb-benchmark;DB_CLOSE_DELAY=0" `
  "-PadbBenchmarkWorkload=mixed" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkWarmupOperations=300" `
  "-PadbBenchmarkOperations=3000" `
  "-PadbBenchmarkTransactionBatchSize=100" `
  "-PadbBenchmarkThreads=8" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_threads_8.properties"
```

## Sixth Round: Key-Path Phase Diagnostics

This round adds `sqlDiagnostics.phaseStats.*` to the SQL diagnostic recorder,
alongside the existing `operationStats`. Phase statistics store only phase
name, count, total latency, average latency, and max latency. They do not store
SQL parameters or row contents. The current coverage includes:

- `ADB_COMMIT_PREPARE`, `ADB_COMMIT_ROW_COUNT_META`, `ADB_COMMIT_WRITE`, and `ADB_COMMIT_POST_REFRESH`
- `ADB_ROW_COUNT_CACHE_HIT`, `ADB_ROW_COUNT_CACHE_MISS`, and `ADB_ROW_COUNT_BASE_SCAN`
- `ADB_TABLE_*` table-engine entry phases such as `PRIMARY_FIND`, `ADD_ROW`, `POINT_LOOKUP_FAST`, and `RANGE_COUNT_FAST`

8-thread `mixed` rerun:

| threads | throughput ops/s | p99 us | max us | Result file |
| ---: | ---: | ---: | ---: | --- |
| 8 | 1383.76 | 9399 | 15994 | `vexra-adb/build/adb-benchmark/jdbc_mixed_phase_threads_8.properties` |

Key phase summary:

| phase | count | avg us | max us | Notes |
| --- | ---: | ---: | ---: | --- |
| `ADB_TABLE_POINT_LOOKUP_FAST ADB_BENCH` | 2320 | 2581 | 18000 | Highest-frequency read path in the mixed workload |
| `ADB_TABLE_RANGE_COUNT_FAST ADB_BENCH` | 648 | 2969 | 12000 | Range count remains a high-latency read path |
| `ADB_TABLE_PRIMARY_FIND ADB_BENCH` | 332 | 3192 | 45000 | Primary-index path used by lookup/write boundaries, with the highest max latency |
| `ADB_TABLE_ADD_ROW ADB_BENCH` | 332 | 2768 | 30000 | The table-engine write entry still amplifies under concurrency |
| `ADB_COMMIT_WRITE` | 40 | 302 | 1422 | Lower-store commit write is not the main cost in this sample |
| `ADB_COMMIT_PREPARE` | 40 | 844 | 10841 | Prepare has occasional tail latency, but less total cost than table/index paths |
| `ADB_ROW_COUNT_CACHE_HIT` | 93 | 3 | 233 | Row-count cache hits are negligible |
| `ADB_ROW_COUNT_CACHE_MISS` | 7 | 1248 | 2111 | Misses appear only in a small initialization/contention window |

Conclusion: the main reason multi-thread throughput does not scale linearly is
not `ADB_COMMIT_WRITE`; it is the table/index entry path represented by
`PRIMARY_FIND`, `POINT_LOOKUP_FAST`, `RANGE_COUNT_FAST`, and `ADD_ROW`. The next
stage should reduce repeated decode/object-boundary work in primary find and
point lookup, and reduce range-count dependence on cursor scanning and the H2
`COUNT` path. Commit write is not the top optimization target for now.

## Next Optimization Targets

| Priority | Target | Verification |
| --- | --- | --- |
| P0 | Route ordinary SQL INSERT into the bulk entry point | Parameterized multi-values `PreparedStatement` and simple literal multi-values `Statement` now route through the ADB JDBC compatibility Driver to `bulkInsertAppendRows`; a future h2db table-level hook is still needed for expressions, triggers, and the full `Insert` grammar |
| P0 | Add commit-stage segmented timing | Separate txn-ref scan, intent read, committed-version write, meta write, and lower-level write batch |
| P0 | Optimize batched writes | Reduce repeated per-row writeBatch, txn-ref scan, and row-count work within one SQL transaction |
| P1 | Remove unnecessary scan/object allocation from point lookup | Validate with allocation profiling and p50/p99 comparison |
| P1 | Avoid extra materialization in SQL COUNT range scans | Compare `LdbStore` scan row iteration and object counts with SQL scan |
| P1 | Optimize primary find and point-lookup object boundaries | Use `phaseStats` to compare avg/max latency and allocation behavior for `PRIMARY_FIND` and `POINT_LOOKUP_FAST` |

## Reproduction Commands

store mixed baseline:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=store" `
  "-PadbBenchmarkStoreDir=D:/work/java2/vexra/vexra-adb/build/adb-benchmark/store/raw-mixed-2" `
  "-PadbBenchmarkWorkload=mixed" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkWarmupOperations=300" `
  "-PadbBenchmarkOperations=3000" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/store_mixed.properties"
```

JDBC mixed batch 100:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=jdbc" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/batch-mixed/adb-benchmark" `
  "-PadbBenchmarkWorkload=mixed" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkWarmupOperations=300" `
  "-PadbBenchmarkOperations=3000" `
  "-PadbBenchmarkTransactionBatchSize=100" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_batch100.properties"
```

## Fourth Round: Point Lookup Optimization

This round adds two conservative fast paths for JDBC primary-key lookup and
for the point-lookup-heavy part of the mixed workload:

1. `TxnManager` caches the latest committed `RowValue` for row keys after a
   successful local commit. A point lookup can skip the committed-version
   prefix scan when the cached `commitTs` is visible to the transaction. To
   preserve checkpoint/restore correctness, each cache hit first verifies that
   the exact physical `VersionKey` still exists in the lower store; stale cache
   entries are invalidated and fall back to the original scan.
2. `AdbPrimaryIndex` keeps a bounded decoded-row cache for primary-key lookups.
   Entries are validated by `RowKey + commitTs`, so updates naturally miss and
   refresh the cache, while deletes and table cleanup remove cached entries.

Measured results:

| Mode | workload | batch | throughput ops/s | p50 us | p95 us | p99 us | max us | Result file |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `jdbc` | `point_lookup` | 1 | 781.45 | 1020 | 2661 | 3748 | 8216 | `vexra-adb/build/adb-benchmark/point_lookup_committed_cache.properties` |
| `jdbc` | `mixed` | 100 | 981.68 | 612 | 3580 | 5215 | 8341 | `vexra-adb/build/adb-benchmark/jdbc_mixed_point_cache.properties` |

Compared with the previous comparable run, standalone `point_lookup` improved
only slightly, from about 770 ops/s to about 781 ops/s. This indicates that the
remaining primary-key lookup cost is still dominated by the H2 executor, JDBC
`ResultSet`, and row-object boundary. The `mixed` batch-100 workload improved
from about 500 ops/s to about 982 ops/s, which shows that committed-version
scans and row decoding become more visible when point lookups are mixed with
writes and commits. Full `.\gradlew.bat :vexra-adb:test --rerun-tasks` passed,
including update/delete cache correctness and backup/restore stale-cache
coverage.

Point lookup reproduction command:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=jdbc" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/point-committed-cache/adb-benchmark;DB_CLOSE_DELAY=0" `
  "-PadbBenchmarkWorkload=point_lookup" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkWarmupOperations=300" `
  "-PadbBenchmarkOperations=3000" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/point_lookup_committed_cache.properties"
```

Mixed workload reproduction command:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=jdbc" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/mixed-point-cache/adb-benchmark;DB_CLOSE_DELAY=0" `
  "-PadbBenchmarkWorkload=mixed" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkWarmupOperations=300" `
  "-PadbBenchmarkOperations=3000" `
  "-PadbBenchmarkTransactionBatchSize=100" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_point_cache.properties"
```
