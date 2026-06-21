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

## Next Optimization Targets

| Priority | Target | Verification |
| --- | --- | --- |
| P0 | Route ordinary SQL INSERT into the bulk entry point | Parameterized multi-values `PreparedStatement` and simple literal multi-values `Statement` now route through the ADB JDBC compatibility Driver to `bulkInsertAppendRows`; a future h2db table-level hook is still needed for expressions, triggers, and the full `Insert` grammar |
| P0 | Add commit-stage segmented timing | Separate txn-ref scan, intent read, committed-version write, meta write, and lower-level write batch |
| P0 | Optimize batched writes | Reduce repeated per-row writeBatch, txn-ref scan, and row-count work within one SQL transaction |
| P1 | Remove unnecessary scan/object allocation from point lookup | Validate with allocation profiling and p50/p99 comparison |
| P1 | Avoid extra materialization in SQL COUNT range scans | Compare `LdbStore` scan row iteration and object counts with SQL scan |
| P1 | Add multi-thread benchmark mode | Check whether lock contention or store write amplification appears after single-thread optimization |

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
