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

## Row-Count Base Snapshot Read-After-Scan Compaction Result

This round further deepens row-count caching. When cold-start
`getBaseRowCount` scans many row-count delta records, the read path writes a
new `VersionRowCountKey` base snapshot using the exact row count computed by
that scan. Later cold starts can begin delta scanning after that base
snapshot's `commitTs`, reducing repeated delta metadata scans.

Implementation constraints:

1. It only writes a new base snapshot and does not delete old delta records, so
   concurrent commits cannot lose newer deltas through a broad `deleteRange`.
2. Compaction is a best-effort optimization. A snapshot write failure does not
   affect the current `COUNT(*)` result.
3. The default threshold is `vexra.adb.rowCount.compactDeltaThreshold=256`;
   set it to `0` or a negative value to disable the optimization.
4. Benchmark runs can control the threshold with
   `-PadbRowCountCompactDeltaThreshold=...`.

Verification passed with `.\gradlew.bat :vexra-adb:test --rerun-tasks`. The new
test covers the first reopen count triggering `ADB_ROW_COUNT_BASE_COMPACT`, and
a second reopen count not triggering compaction again, proving that later reads
start from the new base snapshot.

8-thread mixed result with threshold 16:

| Mode | workload | threads | operations | throughput ops/s | p50 us | p95 us | p99 us | Result file |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `jdbc` | `mixed` | 8 | 3000 | 1221.00 | 2373 | 11121 | 15080 | `vexra-adb/build/adb-benchmark/jdbc_mixed_rowcount_compact_threads_8.properties` |

Diagnostic conclusion:

- This run recorded one `ADB_ROW_COUNT_BASE_COMPACT` phase at about 3268 us.
- `ADB_ROW_COUNT_BASE_SCAN` was still recorded only once, so the single-flight
  cold-start behavior remains effective.
- This optimization targets restart / first `COUNT(*)` scenarios after many
  deltas. It should not be expected to significantly improve online mixed
  throughput. The current mixed window is still dominated by
  `ADB_TABLE_POINT_LOOKUP_FAST`, `ADB_TABLE_ADD_ROW`,
  `ADB_TABLE_PRIMARY_FIND`, and `ADB_TABLE_RANGE_COUNT_FAST`.

Reproduction command:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=jdbc" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-mixed-rowcount-compact-threads-8/adb-benchmark;DB_CLOSE_DELAY=0" `
  "-PadbBenchmarkWorkload=mixed" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkWarmupOperations=300" `
  "-PadbBenchmarkOperations=3000" `
  "-PadbBenchmarkThreads=8" `
  "-PadbRowCountCompactDeltaThreshold=16" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_rowcount_compact_threads_8.properties"
```

## Prepared Point Lookup Value Array Reuse Result

This round continues narrowing the object boundary for the
`SELECT col FROM table WHERE ID = ?` prepared fast path:

1. `AdbPreparedPointLookupPlan` no longer copies the cached `Value[]` on a
   decoded-column cache hit.
2. On a cache miss, it directly passes the `Value[]` returned by
   `RowCodec.decodeColumns(...)` to the read-only `AdbSimpleResultSet`, avoiding
   another array copy after decode.
3. `AdbSimpleResultSet` does not mutate the `Value[]`, and H2 `Value` objects
   are treated as immutable values on this path, so query semantics are
   unchanged.

Verification passed with `.\gradlew.bat :vexra-adb:test --rerun-tasks`.

Reproducible results:

| Mode | workload | threads | operations | throughput ops/s | p50 us | p95 us | p99 us | Result file |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `jdbc` | `point_lookup` | 1 | 3000 | 2373.42 | 332 | 843 | 1252 | `vexra-adb/build/adb-benchmark/point_lookup_value_array_reuse.properties` |
| `jdbc` | `mixed` | 8 | 3000 | 1197.60 | - | - | 15177 | `vexra-adb/build/adb-benchmark/jdbc_mixed_value_array_reuse_threads_8.properties` |

Conclusion:

- Standalone prepared point lookup benefits clearly. `ADB_TABLE_POINT_LOOKUP_FAST`
  averaged about 416 us, with p99 at 1252 us.
- The 8-thread mixed workload did not improve in the same way, which confirms
  that the combined workload is still dominated by `ADB_TABLE_PRIMARY_FIND`,
  `ADB_TABLE_ADD_ROW`, `ADB_TABLE_RANGE_COUNT_FAST`, and the outer
  JDBC/table-engine boundary.
- The next step should keep reducing primary-find visible-row resolution and
  Row construction boundaries, or move to ordinary JDBC insert write-entry
  optimization.

Point lookup reproduction command:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=jdbc" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/point-lookup-value-array-reuse/adb-benchmark;DB_CLOSE_DELAY=0" `
  "-PadbBenchmarkWorkload=point_lookup" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkWarmupOperations=300" `
  "-PadbBenchmarkOperations=3000" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/point_lookup_value_array_reuse.properties"
```

Mixed 8-thread reproduction command:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=jdbc" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-mixed-value-array-reuse-threads-8/adb-benchmark;DB_CLOSE_DELAY=0" `
  "-PadbBenchmarkWorkload=mixed" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkWarmupOperations=300" `
  "-PadbBenchmarkOperations=3000" `
  "-PadbBenchmarkThreads=8" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_value_array_reuse_threads_8.properties"
```

## Point Lookup / Primary Find Detailed Diagnostics Toggle

This round adds optional fine-grained phases for point lookup and primary find:

- `ADB_POINT_LOOKUP_VISIBLE_ROW`: MVCC visible-row resolution in prepared point lookup.
- `ADB_POINT_LOOKUP_RESULT_BUILD`: fast-path ResultSet creation in prepared point lookup.
- `ADB_PRIMARY_FIND_VISIBLE_ROW`: MVCC visible-row resolution in the H2 primary-find point path.
- `ADB_PRIMARY_FIND_ROW_CACHE_HIT` / `ADB_PRIMARY_FIND_ROW_CACHE_MISS`: decoded-row cache behavior in primary find.

These phases call the SQL diagnostic recorder, so they are disabled by default
to avoid synchronized statistics overhead on the hot point-lookup path. Enable
them in benchmark runs with `-PadbBenchmarkDetailedDiagnostics=true`, or at
runtime with `-Dvexra.adb.sql.diagnostic.detail=true`.

Default diagnostics-off mixed 8-thread result:

| Mode | workload | threads | operations | throughput ops/s | p99 us | Result file |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| `jdbc` | `mixed` | 8 | 3000 | 1300.95 | 13488 | `vexra-adb/build/adb-benchmark/jdbc_mixed_detail_toggle_threads_8.properties` |

Detailed diagnostics-on mixed 8-thread result:

| Mode | workload | threads | operations | throughput ops/s | p99 us | Result file |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| `jdbc` | `mixed` | 8 | 3000 | 1257.33 | 13707 | `vexra-adb/build/adb-benchmark/jdbc_mixed_detail_on_threads_8.properties` |

Diagnostic conclusion:

- `ADB_TABLE_POINT_LOOKUP_FAST` averaged about 2360 us. Inside it,
  `ADB_POINT_LOOKUP_VISIBLE_ROW` averaged about 259 us,
  `ADB_POINT_LOOKUP_RESULT_BUILD` averaged about 16 us, and
  `ADB_POINT_LOOKUP_DECODE_CACHE_MISS` averaged about 8 us.
- `ADB_TABLE_PRIMARY_FIND` averaged about 3292 us, with
  `ADB_PRIMARY_FIND_VISIBLE_ROW` averaging about 1404 us.
- Therefore, the next high-value point lookup / primary find work should not
  keep focusing on column-value decoding first. It should reduce the
  H2/JDBC/table-engine call boundary, primary-find visible-row resolution, and
  Row/ResultSet object boundary.

Default mixed reproduction command:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=jdbc" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-mixed-detail-toggle-threads-8/adb-benchmark;DB_CLOSE_DELAY=0" `
  "-PadbBenchmarkWorkload=mixed" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkWarmupOperations=300" `
  "-PadbBenchmarkOperations=3000" `
  "-PadbBenchmarkThreads=8" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_detail_toggle_threads_8.properties"
```

Detailed diagnostics mixed reproduction command:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=jdbc" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-mixed-detail-on-threads-8/adb-benchmark;DB_CLOSE_DELAY=0" `
  "-PadbBenchmarkWorkload=mixed" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkWarmupOperations=300" `
  "-PadbBenchmarkOperations=3000" `
  "-PadbBenchmarkThreads=8" `
  "-PadbBenchmarkDetailedDiagnostics=true" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_detail_on_threads_8.properties"
```

## Row-Count Cold-Start Single-Flight Result

The previous 8-thread mixed report showed 8 `ADB_ROW_COUNT_CACHE_MISS` /
`ADB_ROW_COUNT_BASE_SCAN` events during concurrent cold start. Each thread was
scanning the same table's row-count baseline and delta metadata. This round
changes `TxnManager.getCachedBaseRowCount` to use per-table single-flight
loading:

1. For the first miss of the same `TabId`, only one thread runs
   `getBaseRowCount`.
2. Other concurrent threads wait for that table load to finish, then read the
   cached value and record `ADB_ROW_COUNT_CACHE_WAIT_HIT`.
3. Existing post-commit delta refresh and truncate/epoch invalidation continue
   to use `rowCountCache`, so row-count visibility semantics are unchanged.

The new integration test `concurrentTableCountLoadsBaseRowCountOnce` starts 8
concurrent JDBC connections that all execute `SELECT COUNT(*) FROM TEST`. It
verifies the returned count and checks that `ADB_ROW_COUNT_CACHE_MISS` is
recorded only once.

Verification and result:

| Mode | workload | threads | operations | throughput ops/s | p50 us | p95 us | p99 us | Result file |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `jdbc` | `mixed` | 8 | 3000 | 1343.48 | 2241 | 10603 | 13751 | `vexra-adb/build/adb-benchmark/jdbc_mixed_rowcount_singleflight_threads_8.properties` |

Compared with the previous 8-thread mixed result, `1148.11 ops/s` and p99
`15640us`, throughput improved by about 17% and p99 decreased by about 12%.
The phase detail confirms that `ADB_ROW_COUNT_CACHE_MISS` dropped from 8
events to 1 event, while `ADB_ROW_COUNT_CACHE_WAIT_HIT` recorded 7 events.

Remaining bottlenecks are still concentrated in:

- `ADB_TABLE_POINT_LOOKUP_FAST`: highest total time; the next step should keep
  reducing the JDBC fast path to row-object boundary cost.
- `ADB_TABLE_ADD_ROW` / `ADB_TABLE_PRIMARY_FIND`: write and primary-find entry
  paths remain high-latency stages in the mixed workload.
- `ADB_TABLE_RANGE_COUNT_FAST`: the internal count-only path is optimized, but
  the outer table-engine/JDBC boundary still has fixed overhead.

Reproduction command:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=jdbc" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-mixed-rowcount-singleflight-threads-8/adb-benchmark;DB_CLOSE_DELAY=0" `
  "-PadbBenchmarkWorkload=mixed" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkWarmupOperations=300" `
  "-PadbBenchmarkOperations=3000" `
  "-PadbBenchmarkThreads=8" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_rowcount_singleflight_threads_8.properties"
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
incomplete parameters, and literal expressions continue to use the original
h2db path.

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
`PreparedStatement` SQL and simple literal `Statement` SQL for `VALUES`
inserts, including both single-row and multi-row forms. It does not yet cover
`INSERT ... SELECT`, `DEFAULT VALUES`, literal expressions/functions,
`ON DUPLICATE KEY`, `RETURNING`, or the full
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

## Round 12 JDBC Single-Row INSERT Fast Path

This round extends the `jdbc:adb:*` compatibility Driver's ordinary
`INSERT INTO ... VALUES ...` automatic bulk path from multi-values statements
to single-row values statements:

```sql
INSERT INTO TEST(ID, NAME) VALUES (?, ?)
INSERT INTO TEST(ID, NAME) VALUES (1, 'a')
```

The matching boundary remains conservative: the target table must be an
`AdbTable`, the column list must be explicit, PreparedStatement tuples must be
made only of `?` parameters, and Statement literals only support simple
numbers, strings, booleans, and `NULL`. Expressions, functions, subqueries,
`DEFAULT VALUES`, `ON DUPLICATE KEY`, and `RETURNING` continue to fall back to
h2db's original execution path. The benchmark's single-row write statement now
uses ordinary `INSERT INTO` instead of `MERGE INTO`, so the `insert` and
`mixed` workloads can exercise ordinary JDBC single-row writes directly.

Measured results:

| Mode | Workload | Batch | Diagnostics | Throughput ops/s | p50 us | p95 us | p99 us | max us | Result file |
| --- | --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| `jdbc` | `insert` | 1 | on | 1044.93 | 933 | 1528 | 3087 | 11188 | `vexra-adb/build/adb-benchmark/jdbc_insert_single_bulk_stage.properties` |
| `jdbc` | `mixed` | 100 | on | 2024.29 | 390 | 1025 | 1920 | 6522 | `vexra-adb/build/adb-benchmark/jdbc_mixed_single_bulk_stage.properties` |

Diagnostics show that the single-row insert measured window records
`ADB_TABLE_BULK_ADD_ROW ADB_BENCH` 2000 times and no
`ADB_TABLE_ADD_ROW ADB_BENCH`; the mixed write portion records
`ADB_TABLE_BULK_ADD_ROW ADB_BENCH` 200 times, while point lookup and range
count continue to use their existing fast paths. New integration tests cover
prepared single-row INSERT and literal single-row INSERT bulk hits, while the
expression-literal fallback test remains in place.

Single-row insert reproduction command:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=jdbc" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/single-insert-bulk-stage/adb-benchmark;DB_CLOSE_DELAY=0" `
  "-PadbBenchmarkWorkload=insert" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkWarmupOperations=200" `
  "-PadbBenchmarkOperations=2000" `
  "-PadbBenchmarkStatementBatchSize=1" `
  "-PadbBenchmarkTransactionBatchSize=100" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_insert_single_bulk_stage.properties"
```

Mixed reproduction command:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=jdbc" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/mixed-single-insert-bulk-stage/adb-benchmark;DB_CLOSE_DELAY=0" `
  "-PadbBenchmarkWorkload=mixed" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkWarmupOperations=200" `
  "-PadbBenchmarkOperations=2000" `
  "-PadbBenchmarkTransactionBatchSize=100" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_single_bulk_stage.properties"
```

## Round 13 `PRIMARY_FIND` Object-Boundary Diagnostics and Decode Path

This round splits the H2 primary-index point lookup object boundary into finer
diagnostic phases and removes one temporary Row round-trip on cache misses. The
old `AdbPrimaryIndex.decodePointRow` cache-miss path first called
`RowCodec.decode` to build a full H2 `Row`, then copied that Row back into a
`Value[]` for the decoded-row cache. The new path is:

1. `RowCodec.decodeRowValues` decodes the payload directly into `Value[]`.
2. The decoded-row cache stores the `Value[]`.
3. The H2 `DefaultRow` is created from `Value[]` only at the cursor boundary.

New detailed diagnostic phases:

| phase | Meaning |
| --- | --- |
| `ADB_PRIMARY_FIND_ROW_DECODE` | Full payload-to-`Value[]` column decoding |
| `ADB_PRIMARY_FIND_ROW_BUILD` | `Value[]` to H2 `DefaultRow` object boundary |

This round also adds a `primary_find` benchmark workload. It uses ordinary
`Statement` SQL, `SELECT NAME FROM ADB_BENCH WHERE ID = <id>`, to bypass the
prepared point-lookup fast path and exercise H2 `AdbPrimaryIndex.find`
directly.

Measured result:

| Mode | Workload | Diagnostics | Throughput ops/s | p50 us | p95 us | p99 us | max us | Result file |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| `jdbc` | `primary_find` | detailed on | 435.29 | 2174 | 3395 | 4350 | 8600 | `vexra-adb/build/adb-benchmark/primary_find_row_boundary_stage.properties` |

Main phase summary:

| phase | count | avg us | max us |
| --- | ---: | ---: | ---: |
| `ADB_TABLE_PRIMARY_FIND ADB_BENCH` | 3000 | 448 | 3000 |
| `ADB_PRIMARY_FIND_VISIBLE_ROW` | 3000 | 6 | 108 |
| `ADB_PRIMARY_FIND_ROW_BUILD` | 3000 | 0 | 88 |
| `ADB_PRIMARY_FIND_ROW_DECODE` | 2700 | 5 | 85 |
| `ADB_PRIMARY_FIND_ROW_CACHE_HIT` | 300 | 2 | 78 |
| `ADB_PRIMARY_FIND_ROW_CACHE_MISS` | 2700 | 8 | 241 |

Conclusion: primary-find visible-row resolution, payload decoding, and H2 Row
construction are now visible as separate phases, and the cache-miss path no
longer creates a temporary Row just to populate the decoded cache. Overall
`primary_find` throughput is still far below the prepared point-lookup fast
path, which suggests the remaining cost is more likely in H2 Statement
parsing/execution, row-count calls, and the outer ResultSet boundary than in
payload decoding alone.

`primary_find` reproduction command:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=jdbc" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/primary-find-row-boundary-stage/adb-benchmark;DB_CLOSE_DELAY=0" `
  "-PadbBenchmarkWorkload=primary_find" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkWarmupOperations=300" `
  "-PadbBenchmarkOperations=3000" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/primary_find_row_boundary_stage.properties" `
  "-PadbBenchmarkDetailedDiagnostics=true"
```

## Round 14 Range Count ResultSet / Prefix Fixed-Cost Result

This round reduces fixed overhead around `ADB_TABLE_RANGE_COUNT_FAST`:

1. `AdbPreparedRangeCountPlan` caches the `RowPrefix` for the current `TabId`.
   Repeated prepared range-count executions within the same table epoch avoid
   rebuilding the prefix. If truncate / DDL advances the epoch, the changed
   `TabId` automatically rebuilds the cached prefix.
2. `AdbSimpleResultSet` adds a single-column long handler, so the `COUNT(*)`
   fast path no longer allocates `Value[]` / `ValueBigint` for every query.
   `findColumn("COUNT(*)")`, `getLong("COUNT(*)")`, and `getString(1)` remain
   supported.

Measured results:

| Mode | Workload | Diagnostics | Throughput ops/s | p50 us | p95 us | p99 us | max us | Result file |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| `jdbc` | `range_scan` | on | 1144.60 | 701 | 1959 | 2682 | 6749 | `vexra-adb/build/adb-benchmark/range_count_resultset_stage.properties` |
| `jdbc` | `mixed` | on | 877.45 | 1086 | 1827 | 3883 | 8169 | `vexra-adb/build/adb-benchmark/jdbc_mixed_range_resultset_stage.properties` |

Main phase summary:

| workload | phase | count | avg us | max us |
| --- | --- | ---: | ---: | ---: |
| `range_scan` | `ADB_RANGE_COUNT_VISIBLE_COUNT` | 3000 | 318 | 6018 |
| `range_scan` | `ADB_TABLE_RANGE_COUNT_FAST ADB_BENCH` | 3000 | 868 | 6000 |
| `mixed` | `ADB_RANGE_COUNT_VISIBLE_COUNT` | 600 | 412 | 6783 |
| `mixed` | `ADB_TABLE_RANGE_COUNT_FAST ADB_BENCH` | 600 | 1408 | 9000 |

Conclusion: this round removes small allocations from the range-count fast
path, but the short benchmark did not beat Round 9's
`range_count_fast_stage` result of 1388.89 ops/s, and the mixed result is below
the previous single-row insert stage's short window. The remaining range-count
opportunity is therefore unlikely to be a single `ResultSet` / `Value[]`
allocation; it is more likely in visible-row scan cost, query-execution
variance, and contention with point lookup / commit work in mixed runs. Future
range-count work should focus on block-level / segment counts or metadata-level
statistics for common wide ranges.

Range-count reproduction command:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=jdbc" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/range-count-resultset-stage/adb-benchmark;DB_CLOSE_DELAY=0" `
  "-PadbBenchmarkWorkload=range_scan" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkWarmupOperations=300" `
  "-PadbBenchmarkOperations=3000" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/range_count_resultset_stage.properties"
```

Mixed reproduction command:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=jdbc" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/mixed-range-resultset-stage/adb-benchmark;DB_CLOSE_DELAY=0" `
  "-PadbBenchmarkWorkload=mixed" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkWarmupOperations=300" `
  "-PadbBenchmarkOperations=3000" `
  "-PadbBenchmarkTransactionBatchSize=100" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_range_resultset_stage.properties"
```

## Round 15 Benchmark Allocation Metrics

This round adds JVM thread allocation-byte metrics to the benchmark measured
window, so object cost is visible without inferring it only from latency.
The implementation uses `com.sun.management.ThreadMXBean`:

- Single-thread `jdbc`, `jdbc_bulk`, `txn`, and `store` modes record the current
  thread's measured-window allocation.
- Multi-thread `jdbc` mode records allocation inside each worker's measured
  window and reports the aggregate.
- If the current JVM does not support thread allocation tracking, the report
  records `allocation.supported=false` and the benchmark still runs normally.

New properties:

| Field | Meaning |
| --- | --- |
| `allocation.supported` | Whether the current JVM supports thread allocation-byte tracking |
| `allocation.totalBytes` | Total allocated bytes in the measured window |
| `allocation.bytesPerOperation` | Average allocated bytes per operation |

Initial measured results:

| Mode | Workload | Throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | Result file |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| `jdbc` | `point_lookup` | 2155.17 | 364 | 989 | 1439 | 10037 | `vexra-adb/build/adb-benchmark/point_lookup_allocation_stage.properties` |
| `jdbc` | `mixed` | 934.87 | 1031 | 1769 | 3479 | 275308 | `vexra-adb/build/adb-benchmark/jdbc_mixed_allocation_stage.properties` |

Then the same benchmark shape was used to split allocation by workload:

| Mode | Workload | Throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | Main operation | Result file |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- | --- |
| `jdbc` | `insert` | 1004.35 | 882 | 1974 | 3037 | 13399 | `ADB_TABLE_BULK_ADD_ROW` | `vexra-adb/build/adb-benchmark/jdbc_insert_allocation_stage.properties` |
| `jdbc` | `point_lookup` | 2155.17 | 364 | 989 | 1439 | 10037 | `ADB_TABLE_POINT_LOOKUP_FAST` | `vexra-adb/build/adb-benchmark/point_lookup_allocation_stage.properties` |
| `jdbc` | `primary_find` | 431.97 | 1882 | 4476 | 5536 | 51285 | `ADB_TABLE_PRIMARY_FIND` / `ADB_TABLE_ROW_COUNT` | `vexra-adb/build/adb-benchmark/primary_find_allocation_stage.properties` |
| `jdbc` | `table_count` | 1157.41 | 831 | 1517 | 1883 | 9193 | `ADB_TABLE_TABLE_COUNT_FAST` | `vexra-adb/build/adb-benchmark/table_count_allocation_stage.properties` |
| `jdbc` | `range_scan` | 1209.68 | 687 | 1550 | 2181 | 1245527 | `ADB_TABLE_RANGE_COUNT_FAST` | `vexra-adb/build/adb-benchmark/range_count_allocation_stage.properties` |
| `jdbc` | `mixed` | 934.87 | 1031 | 1769 | 3479 | 275308 | bulk add / point lookup / range count | `vexra-adb/build/adb-benchmark/jdbc_mixed_allocation_stage.properties` |

Conclusion: allocation metrics now make object cost directly visible. Prepared
point lookup, table count, and single-row insert are all around `9KB-14KB/op`.
`primary_find` is about `51KB/op`, while `range_scan` reaches about `1.25MB/op`.
The mixed workload's `275KB/op` result lines up with its 20% range-count share.
Therefore, if the next optimization targets allocation, the highest-value path
is not more single-row write or prepared point-lookup work; it is reducing
range-count per-row visibility allocation, or adding row-count segment /
block-level counts. `primary_find` is still a good candidate for bypassing H2
Statement / row-count outer boundaries.

Point-lookup allocation reproduction command:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=jdbc" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/point-lookup-allocation-stage/adb-benchmark;DB_CLOSE_DELAY=0" `
  "-PadbBenchmarkWorkload=point_lookup" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkWarmupOperations=300" `
  "-PadbBenchmarkOperations=3000" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/point_lookup_allocation_stage.properties"
```

Mixed allocation reproduction command:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=jdbc" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/mixed-allocation-stage/adb-benchmark;DB_CLOSE_DELAY=0" `
  "-PadbBenchmarkWorkload=mixed" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkWarmupOperations=300" `
  "-PadbBenchmarkOperations=3000" `
  "-PadbBenchmarkTransactionBatchSize=100" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_allocation_stage.properties"
```

To reproduce the workload allocation split with the same parameter shape,
replace `-PadbBenchmarkWorkload` with `insert`, `range_scan`, `table_count`, or
`primary_find`, and write to the corresponding output file:

| workload | output |
| --- | --- |
| `insert` | `vexra-adb/build/adb-benchmark/jdbc_insert_allocation_stage.properties` |
| `range_scan` | `vexra-adb/build/adb-benchmark/range_count_allocation_stage.properties` |
| `table_count` | `vexra-adb/build/adb-benchmark/table_count_allocation_stage.properties` |
| `primary_find` | `vexra-adb/build/adb-benchmark/primary_find_allocation_stage.properties` |

## Round 16 Range Count Raw-Key Low-Allocation Scan

This round targets the high allocation hotspot exposed by Round 15's
`range_scan` split. `TxnManager.countVisibleRows` now has a raw-key fast path
when the current transaction has no local writes:

1. If `txn.getWriteSet().isEmpty()`, range count no longer constructs
   `VersionKey`, `DataKey`, and row-prefix byte arrays for every logical row.
   It decodes `rowId` and the committed flag directly from the fixed offsets
   in the version-row key.
2. Logical-row grouping compares the first 21 bytes of the raw key and still
   uses `RowValue.decodeMetadata` for `commitTs`, `deleted`, and payload
   existence checks.
3. As soon as the transaction has local insert/delete entries, the old
   conservative path remains in use, preserving same-transaction local write,
   rollback, and store-version override semantics.
4. `ADB_RANGE_COUNT_VISIBLE_COUNT_RAW` was added as a phase metric to confirm
   whether prepared range count hits the raw-key path.

Verification command:

```powershell
.\gradlew.bat :vexra-adb:test --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest
```

Reproducible results:

| Mode | Workload | Throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | Result file |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| `jdbc` | `range_scan` | 1676.91 | 419 | 1445 | 1880 | 501959 | `vexra-adb/build/adb-benchmark/range_count_raw_stage.properties` |
| `jdbc` | `mixed` | 1417.77 | 473 | 1641 | 3282 | 111014 | `vexra-adb/build/adb-benchmark/jdbc_mixed_range_raw_stage.properties` |

Compared with Round 15's allocation split, `range_scan` improves from
`1209.68 ops/s` to `1676.91 ops/s`, and allocation drops from
`1245527 bytes/op` to `501959 bytes/op`. The `mixed` workload improves from
`934.87 ops/s` to `1417.77 ops/s`, with allocation dropping from
`275308 bytes/op` to `111014 bytes/op`. This confirms that the heaviest
current range-count cost is per-row key materialization rather than ldb itself.
Further range-count work should move toward segment / block-level count; for
mixed-workload optimization, priority should return to `POINT_LOOKUP_FAST`,
`PRIMARY_FIND`, and ordinary write-entry object boundaries.

Range-count raw-key reproduction command:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=jdbc" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/range-count-raw-stage/adb-benchmark;DB_CLOSE_DELAY=0" `
  "-PadbBenchmarkWorkload=range_scan" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkWarmupOperations=300" `
  "-PadbBenchmarkOperations=3000" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/range_count_raw_stage.properties"
```

Mixed reproduction command:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=jdbc" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/mixed-range-raw-stage/adb-benchmark;DB_CLOSE_DELAY=0" `
  "-PadbBenchmarkWorkload=mixed" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkBatchSize=100" `
  "-PadbBenchmarkWarmupOperations=300" `
  "-PadbBenchmarkOperations=3000" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_range_raw_stage.properties"
```

## Round 17 Prepared Point Lookup Single-Value Fast Path

This round further narrows the object boundary for prepared primary-key
lookups shaped as `SELECT col FROM table WHERE ID = ?`:

1. `RowCodec.decodeColumn` adds a single-column payload decoder, so
   single-column projection no longer builds a `Value[]` first.
2. `AdbPreparedPointLookupPlan` keeps a `rowId + commitTs -> Value` cache for
   single-column projections. Multi-column projections and `SELECT *` continue
   to use the existing `Value[]` cache.
3. `AdbSimpleResultSet.singleValue` adds a single-value ResultSet handler, so
   the fast path does not allocate an extra array after it already has one
   `Value`.
4. Existing `ADB_POINT_LOOKUP_DECODE_CACHE_HIT/MISS` and
   `ADB_POINT_LOOKUP_RESULT_BUILD` phase metrics remain available for
   comparison with earlier point-lookup rounds.

Verification commands:

```powershell
.\gradlew.bat :vexra-adb:test --tests net.xdob.vexra.adb.db.RowCodecTest
.\gradlew.bat :vexra-adb:test --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest
.\gradlew.bat :vexra-adb:test
```

Reproducible results:

| Mode | Workload | Throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | Result file |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| `jdbc` | `point_lookup` | 2685.77 | 318 | 706 | 1111 | 9904 | `vexra-adb/build/adb-benchmark/point_lookup_single_value_stage.properties` |
| `jdbc` | `mixed` | 1699.72 | 401 | 1450 | 2619 | 111358 | `vexra-adb/build/adb-benchmark/jdbc_mixed_point_single_value_stage.properties` |

Compared with the previous comparable run after `range_count_raw_stage`,
`mixed` improves from `1417.77 ops/s` to `1699.72 ops/s`, and p99 drops from
`3282us` to `2619us`. `ADB_TABLE_POINT_LOOKUP_FAST` average latency drops from
about `536us` to about `448us`. Allocation is mostly flat, which means this
round mainly reduces small hot-path object and method-boundary cost rather
than large payload or key materialization. If the next stage continues read
optimization, the best target is the `PRIMARY_FIND` H2 `SingleRowCursor` /
`DefaultRow` boundary. For overall mixed throughput, ordinary write-entry
cost in `ADB_TABLE_BULK_ADD_ROW` and `ADB_TABLE_ADD_ROW` remains worth
compressing.

Point-lookup reproduction command:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=jdbc" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/point-single-value-stage/adb-benchmark;DB_CLOSE_DELAY=0" `
  "-PadbBenchmarkWorkload=point_lookup" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkWarmupOperations=300" `
  "-PadbBenchmarkOperations=3000" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/point_lookup_single_value_stage.properties"
```

Mixed reproduction command:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=jdbc" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/mixed-point-single-value-stage/adb-benchmark;DB_CLOSE_DELAY=0" `
  "-PadbBenchmarkWorkload=mixed" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkBatchSize=100" `
  "-PadbBenchmarkWarmupOperations=300" `
  "-PadbBenchmarkOperations=3000" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_point_single_value_stage.properties"
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

## Seventh Round: Prepared Point-Lookup Decoded-Column Cache

This round adds a decoded-column cache to `AdbPreparedPointLookupPlan`, guarded
by `rowId + commitTs`:

1. Cache hits reuse decoded `Value[]` entries, but still copy the array before
   handing it to `AdbSimpleResultSet`, so result sets do not share a mutable
   array.
2. Only committed versions with `commitTs > 0` are cached; uncommitted
   same-transaction versions are decoded directly to avoid stale reads under
   `commitTs=0`.
3. Missing rows remove the rowId cache entry. Committed updates and deletes
   naturally invalidate through changed commitTs or visibility.
4. The benchmark now reports `ADB_POINT_LOOKUP_DECODE_CACHE_HIT/MISS`, and an
   integration test covers repeated lookup, committed update, and delete through
   the same prepared statement.

Measured results:

| workload | threads | throughput ops/s | p99 us | max us | Result file |
| --- | ---: | ---: | ---: | ---: | --- |
| `point_lookup` | 1 | 1654.72 | 1606 | 2632 | `vexra-adb/build/adb-benchmark/point_lookup_decode_cache_stage.properties` |
| `mixed` | 8 | 1474.93 | 8060 | 10721 | `vexra-adb/build/adb-benchmark/jdbc_mixed_decode_cache_threads_8.properties` |

Phase summary:

| workload | phase | count | avg us | max us |
| --- | --- | ---: | ---: | ---: |
| `point_lookup` | `ADB_POINT_LOOKUP_DECODE_CACHE_HIT` | 300 | 0 | 10 |
| `point_lookup` | `ADB_POINT_LOOKUP_DECODE_CACHE_MISS` | 2700 | 4 | 418 |
| `point_lookup` | `ADB_TABLE_POINT_LOOKUP_FAST ADB_BENCH` | 3000 | 599 | 3000 |
| `mixed` | `ADB_POINT_LOOKUP_DECODE_CACHE_HIT` | 28 | 7 | 142 |
| `mixed` | `ADB_POINT_LOOKUP_DECODE_CACHE_MISS` | 2292 | 10 | 1587 |
| `mixed` | `ADB_TABLE_POINT_LOOKUP_FAST ADB_BENCH` | 2320 | 2530 | 15000 |

Conclusion: this cache helps prepared point lookups when there is key locality,
but the current benchmark spreads keys widely, so hit rate is low. Because
`decodeColumns` itself averages only single-digit microseconds, the remaining
mixed-workload bottleneck is still the broader table/index entry path:
`PRIMARY_FIND`, `RANGE_COUNT_FAST`, and `ADD_ROW`. The next stage should reduce
range-count cursor scanning cost, or split `PRIMARY_FIND` into `getVisible`,
cache lookup, Row creation, and H2 cursor/result-boundary timing.

## Next Optimization Targets

| Priority | Target | Verification |
| --- | --- | --- |
| P0 | Route ordinary SQL INSERT into the bulk entry point | Parameterized multi-values `PreparedStatement` and simple literal multi-values `Statement` now route through the ADB JDBC compatibility Driver to `bulkInsertAppendRows`; a future h2db table-level hook is still needed for expressions, triggers, and the full `Insert` grammar |
| P0 | Optimize batched writes | Reduce repeated per-row writeBatch, txn-ref scan, and row-count work within one SQL transaction |
| P1 | Split primary-find internal stages | Separate `getVisible`, decoded-row cache, Row creation, and H2 cursor/result-boundary timing |
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

## Fifth Round: Range Count Visible-Row Counting

This round moves the JDBC fast path for
`SELECT COUNT(*) FROM table WHERE pk BETWEEN ? AND ?` from the general
`TableScanCursor` path to a count-only scan:

1. `RowValue.decodeMetadata` decodes only `txnId`, `commitTs`, `deleted`, and
   the payload length, avoiding payload byte-array copies for each visible
   version.
2. `TxnManager.countVisibleRows` reuses the existing MVCC visibility boundary
   and range-read routing, and applies the current transaction's local
   write-set before committed store versions when a logical row is scanned.
3. After the store scan, it counts local row writes that are not yet present in
   the store, fixing prepared range count visibility for same-transaction
   inserts.
4. `ADB_RANGE_COUNT_VISIBLE_COUNT` was added as a phase metric so count-only
   scan cost can be separated from the outer `ADB_TABLE_RANGE_COUNT_FAST`
   table-engine entry cost.

Verification passed with `.\gradlew.bat :vexra-adb:test --rerun-tasks`. The new
coverage checks prepared range count visibility across same-transaction insert,
delete, and rollback.

Reproducible results:

| Mode | workload | threads | operations | throughput ops/s | p50 us | p95 us | p99 us | Result file |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `jdbc` | `range_scan` | 1 | 3000 | 1149.87 | 740 | 1788 | 2547 | `vexra-adb/build/adb-benchmark/range_count_visible_count_stage.properties` |
| `jdbc` | `mixed` | 8 | 3000 | 1148.11 | 2175 | 10406 | 15640 | `vexra-adb/build/adb-benchmark/jdbc_mixed_visible_count_threads_8.properties` |

Diagnostic conclusion:

- In the pure range-count run, `ADB_RANGE_COUNT_VISIBLE_COUNT` averaged about
  296 us, while `ADB_TABLE_RANGE_COUNT_FAST` averaged about 859 us. The
  count-only scan reduced internal payload decode work, but the JDBC and
  table-engine entry boundary still has visible fixed cost.
- In the 8-thread mixed run, `ADB_RANGE_COUNT_VISIBLE_COUNT` averaged about
  671 us and `ADB_TABLE_RANGE_COUNT_FAST` averaged about 2776 us. Overall
  throughput did not improve over the previous mixed result, which indicates
  that range-count payload decoding is not the main mixed-workload bottleneck.
- The same mixed report shows the first `ADB_ROW_COUNT_CACHE_MISS` /
  `ADB_ROW_COUNT_BASE_SCAN` at about 70 ms, with the largest total time in
  `ADB_TABLE_POINT_LOOKUP_FAST`; `ADB_TABLE_PRIMARY_FIND` and
  `ADB_TABLE_ADD_ROW` remain high-latency entry points. The next higher-value
  stage should focus on row-count baseline warmup/persistence, point
  lookup/primary-find object boundaries, and write-entry batching.

Range-count reproduction command:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=jdbc" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/range-count-visible-count-stage/adb-benchmark;DB_CLOSE_DELAY=0" `
  "-PadbBenchmarkWorkload=range_scan" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkWarmupOperations=300" `
  "-PadbBenchmarkOperations=3000" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/range_count_visible_count_stage.properties"
```

Mixed 8-thread reproduction command:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=jdbc" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-mixed-visible-count-threads-8/adb-benchmark;DB_CLOSE_DELAY=0" `
  "-PadbBenchmarkWorkload=mixed" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkWarmupOperations=300" `
  "-PadbBenchmarkOperations=3000" `
  "-PadbBenchmarkThreads=8" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_visible_count_threads_8.properties"
```

## Round 18: Primary-Find Cost Diagnostics and Rejected Experiments

This round further splits the outer `PRIMARY_FIND` cost and keeps one
detailed-only diagnostic phase:

- `ADB_PRIMARY_FIND_COST`: time spent while H2 planner calls the primary
  index `getCost` method.
- This phase is recorded only when `vexra.adb.sql.diagnostic.detail=true`, so
  the default benchmark and production hot path do not add extra
  `System.nanoTime()` calls.

Two experimental approaches were validated but not retained as default
behavior:

| Experiment | workload | throughput ops/s | p50 us | p95 us | p99 us | bytes/op | Conclusion |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| Lightweight `Row` view instead of `DefaultRow` | `primary_find` | 302.85 | 3342 | 5512 | 6791 | 51223 | Clear regression from the previous `431.97 ops/s`; rejected |
| Estimated row count in `getCost`, avoiding exact `getRowCount` | `primary_find` | 524.02 | 1942 | 3191 | 4778 | 33770 | Clear standalone primary-find improvement |
| Same estimated-row-count approach | `mixed` | 1135.93 | 713 | 2199 | 3350 | 111425 | Clear mixed-workload regression from the previous `1699.72 ops/s`; rejected |

Conclusion:

- `getCost -> getRowCount` is one real source of standalone primary-find cost.
  Estimated row count removes `ADB_TABLE_ROW_COUNT` from that path and lowers
  allocation.
- The same change can affect H2 optimizer index choice or execution-plan
  stability, causing a major mixed-workload throughput regression. Therefore
  the primary index cost must not be switched to a fixed estimate globally.
- Future primary-find optimization should use `ADB_PRIMARY_FIND_COST` to design
  a narrower strategy: for example, skip exact row-count only for safe primary
  key equality plans, or make the row-count baseline prewarmed and reusable
  without changing optimizer semantics.

Primary-find cost diagnostic reproduction command:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=jdbc" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/primary-find-cost-diagnostic/adb-benchmark;DB_CLOSE_DELAY=0" `
  "-PadbBenchmarkWorkload=primary_find" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkWarmupOperations=300" `
  "-PadbBenchmarkOperations=3000" `
  "-PadbBenchmarkDetailedDiagnostics=true" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/primary_find_cost_diagnostic.properties"
```

## Round 19: Row-Count Cache Prewarm on Open

This round deepens the row-count cache. After an `AdbTable` is constructed, it
now calls `TxnManager.prewarmRowCountCache(TabId)` by default, moving the
row-count base/delta meta scan to database open or table-object recovery time.
The prewarm only fills the in-process cache and does not modify persistent
data. It can be disabled with `-Dvexra.adb.rowCount.prewarm=false` when startup
latency matters more than the first business request.

New diagnostic phases:

- `ADB_ROW_COUNT_PREWARM`: one row-count cache prewarm during table open.
- `ADB_ROW_COUNT_PREWARM_HIT`: prewarm found an existing cache entry.

Verification coverage:

- `rowCountCachePrewarmsAfterReopen`: after reopen, the first `COUNT(*)` hits
  `ADB_ROW_COUNT_CACHE_HIT` and no longer records `ADB_ROW_COUNT_CACHE_MISS`.
- `rowCountCachePrewarmCanBeDisabled`: disabling
  `vexra.adb.rowCount.prewarm` restores lazy loading, so the first `COUNT(*)`
  still records one cache miss.
- `concurrentTableCountLoadsBaseRowCountOnce` now verifies that concurrent
  count queries share the prewarmed cache.

Reproducible results:

| workload | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | Key diagnostics | Result file |
| --- | ---: | ---: | ---: | ---: | ---: | --- | --- |
| `table_count` | 1318.68 | 731 | 1297 | 1844 | 9094 | 3000 `ADB_ROW_COUNT_CACHE_HIT` records in the measured window, no miss/base scan | `vexra-adb/build/adb-benchmark/table_count_prewarm_stage.properties` |
| `primary_find` | 474.61 | 1989 | 3126 | 4087 | 51052 | 6000 `ADB_ROW_COUNT_CACHE_HIT` records in the measured window, no miss/base scan | `vexra-adb/build/adb-benchmark/primary_find_prewarm_stage.properties` |
| `mixed` 8 threads | 1051.16 | 2482 | 8499 | 11684 | 684348 | one prewarm/base scan is recorded after worker connection open; the concurrent benchmark still includes connection/prewarm overhead | `vexra-adb/build/adb-benchmark/jdbc_mixed_prewarm_stage.properties` |

Conclusion:

- Single-thread `table_count` improved from the Round 15 allocation baseline
  `1157.41 ops/s` to `1318.68 ops/s`; `primary_find` improved from
  `431.97 ops/s` to `474.61 ops/s`.
- This keeps H2 optimizer cost semantics unchanged, making it safer than the
  rejected fixed-row-count estimate. The measured window confirms row-count
  reads become cache hits instead of cold miss/base scans.
- The current concurrent mixed benchmark starts timing after worker launch and
  includes each worker's connection and warmup. Therefore this mixed result is
  treated only as a side-effect observation, not as throughput-improvement
  evidence. A future benchmark change should separate `connection/open/prewarm`
  from the measured operation window.

Table-count reproduction command:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=jdbc" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/table-count-prewarm-stage/adb-benchmark;DB_CLOSE_DELAY=0" `
  "-PadbBenchmarkWorkload=table_count" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkWarmupOperations=300" `
  "-PadbBenchmarkOperations=3000" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/table_count_prewarm_stage.properties"
```

Primary-find reproduction command:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=jdbc" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/primary-find-prewarm-stage/adb-benchmark;DB_CLOSE_DELAY=0" `
  "-PadbBenchmarkWorkload=primary_find" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkWarmupOperations=300" `
  "-PadbBenchmarkOperations=3000" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/primary_find_prewarm_stage.properties"
```

## Round 20: Concurrent Benchmark Measured-Window Split

This round fixes the measured window of the concurrent `jdbc` benchmark. The
old implementation started timing immediately after worker launch. Each worker
still had to open a JDBC connection, build `BenchmarkStatements`, run warmup,
and trigger table-open row-count prewarm, so those costs polluted mixed
throughput, allocation, and SQL phase diagnostics.

New measured-window semantics:

1. Workers first open connections, build statements, and finish warmup.
2. After all workers are ready, the main thread calls
   `AdbSqlDiagnosticsRegistry.resetAll()`.
3. The main thread opens the measured-operation gate; only then do workers
   start counted operations and allocation sampling.
4. The report writes `concurrency.measuredWindow=operationsOnly` to mark that
   connection/warmup/prewarm is excluded.

Test coverage:

- `shouldRunConcurrentMixedBenchmarkAgainstLdbUrl` verifies
  `concurrency.measuredWindow=operationsOnly`.
- The same test asserts the measured-window diagnostics do not contain
  `ADB_ROW_COUNT_PREWARM`.

Mixed 8-thread result after the measured-window fix:

| workload | threads | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | Result file |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `mixed` | 8 | 2463.05 | 2370 | 8266 | 11042 | 732973 | `vexra-adb/build/adb-benchmark/jdbc_mixed_measured_window_stage.properties` |

Measured-window phase summary:

| phase | count | avg us | max us |
| --- | ---: | ---: | ---: |
| `ADB_TABLE_POINT_LOOKUP_FAST ADB_BENCH` | 2100 | 2432 | 11000 |
| `ADB_TABLE_BULK_ADD_ROW ADB_BENCH` | 300 | 2876 | 11000 |
| `ADB_TABLE_RANGE_COUNT_FAST ADB_BENCH` | 600 | 2545 | 10000 |
| `ADB_RANGE_COUNT_VISIBLE_COUNT` | 600 | 355 | 2960 |
| `ADB_POINT_LOOKUP_DECODE_CACHE_MISS` | 2072 | 7 | 112 |

Conclusion:

- The mixed throughput measurement improved from the previous
  connection/prewarm-polluted `1051.16 ops/s` to `2463.05 ops/s`, which is a
  cleaner counted-operation result.
- The measured window no longer contains row-count prewarm phases, so future
  mixed runs can be used to judge the real entry cost of point lookup, bulk
  add, and range count.
- The remaining major hotspots are still at the table-engine/JDBC entry layer:
  `POINT_LOOKUP_FAST`, `BULK_ADD_ROW`, and `RANGE_COUNT_FAST` all average about
  2.4ms-2.9ms, while inner decode/count phases are no longer the largest cost.

Mixed reproduction command:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  "-PadbBenchmarkMode=jdbc" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-mixed-measured-window-stage/adb-benchmark;DB_CLOSE_DELAY=0" `
  "-PadbBenchmarkWorkload=mixed" `
  "-PadbBenchmarkRows=5000" `
  "-PadbBenchmarkWarmupOperations=300" `
  "-PadbBenchmarkOperations=3000" `
  "-PadbBenchmarkThreads=8" `
  "-PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_measured_window_stage.properties"
```

## Round 21: JFR Benchmark Entry and Rejected Direct-Cache Experiment

This round tried replacing the prepared point lookup
`ConcurrentHashMap<Long, ...>` decoded-column cache with a direct rowId-mapped
cache to reduce `Long` boxing and CHM access cost. The result was not stable
enough, so the code was not retained:

| Experiment | workload | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | Conclusion |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| direct cache | `point_lookup` | 2074.69 | 387 | 709 | 1316 | 10015 | Below the historical single-value fast-path result; rejected |
| direct cache | `mixed` 8 threads | 2479.34 | 2304 | 7852 | 11423 | 732992 | Close to Round 20 `2463.05 ops/s`, with worse p99; rejected |

To continue diagnosing the outer object and call boundaries of
`POINT_LOOKUP_FAST`, `BULK_ADD_ROW`, and `RANGE_COUNT_FAST`, this round adds a
JFR profiling entry:

1. `:vexra-adb:adbBenchmark` now supports `-PadbBenchmarkJvmArgs=...`, allowing
   `-XX:StartFlightRecording` and similar JVM arguments to be passed to the
   benchmark JVM.
2. `scripts/adb-benchmark-jfr.ps1` writes both `.jfr` and `.properties` files
   under `vexra-adb/build/adb-benchmark/jfr` by default.
3. The local JDK 8 requires `-XX:+UnlockCommercialFeatures`; the script adds it
   by default.

Smoke test:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\adb-benchmark-jfr.ps1 `
  -Workload point_lookup `
  -Rows 20 `
  -WarmupOperations 2 `
  -Operations 5 `
  -Threads 1 `
  -OutputDir vexra-adb/build/adb-benchmark/jfr-smoke
```

Smoke test result:

| File | Result |
| --- | --- |
| `vexra-adb/build/adb-benchmark/jfr-smoke/adb-point_lookup-20260622-110910.jfr` | generated, 216528 bytes |
| `vexra-adb/build/adb-benchmark/jfr-smoke/adb-point_lookup-20260622-110910.properties` | `passed=true`, `point_lookup` 5 operations |

Next analysis guidance:

- Open the `.jfr` file with JDK Mission Control and inspect allocation hot
  spots and sampled methods first.
- Compare `point_lookup` and `mixed` stacks for `java.lang.reflect.Proxy`,
  `AdbSimpleResultSet` handlers, `TxnMap2.getVisible`,
  `RowValue.decodeValue`, and `PreparedStatement` proxy calls.
- If JFR proves Proxy/handler allocation is dominant, implement a dedicated
  `ResultSet` class next; otherwise continue reducing `TxnMap2.getVisible` and
  table-engine/JDBC entry overhead.

## Round 22: Mixed 8-Thread JFR Capture and Bulk Write Entry Cleanup

This round first ran the full `mixed` 8-thread JFR benchmark required by the
performance plan, instead of guessing whether Proxy / ResultSet allocations are
dominant:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\adb-benchmark-jfr.ps1 `
  -Workload mixed `
  -Rows 5000 `
  -WarmupOperations 300 `
  -Operations 3000 `
  -Threads 8 `
  -OutputDir vexra-adb/build/adb-benchmark/jfr-mixed-8
```

Capture result:

| File | Result |
| --- | --- |
| `vexra-adb/build/adb-benchmark/jfr-mixed-8/adb-mixed-20260622-112110.jfr` | generated, 947880 bytes |
| `vexra-adb/build/adb-benchmark/jfr-mixed-8/adb-mixed-20260622-112110.properties` | `passed=true`, `mixed` 3000 operations |

The local JDK 8 only provides `jcmd`; it does not provide `jfr.exe`, JDK
Mission Control, or a local JFR parser jar. Therefore this round could not
print allocation hot spots offline on this machine. To make the analysis
repeatable on a machine with tooling, this round adds
`scripts/adb-jfr-hotspots.ps1`. With a JDK 11+ `jfr` CLI, it exports:

1. `summary.txt`
2. `allocation-events.txt`
3. `execution-samples.txt`
4. `adb-focus.txt`, matching `java.lang.reflect.Proxy`,
   `AdbSimpleResultSet`, `AdbPreparedStatementProxy`, `TxnMap2.getVisible`,
   `DefaultVisibleRowResolver`, `RowValue.decodeValue`, `RowCodec`, and
   commit/write-batch related terms.

Usage:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\adb-jfr-hotspots.ps1 `
  -JfrFile vexra-adb/build/adb-benchmark/jfr-mixed-8/adb-mixed-20260622-112110.jfr
```

The same JFR capture produced this `mixed` 8-thread result:

| workload | threads | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | Result file |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `mixed` | 8 | 2401.92 | 2268 | 7832 | 11594 | 683419 | `vexra-adb/build/adb-benchmark/jfr-mixed-8/adb-mixed-20260622-112110.properties` |

Because local JFR tooling still has not proven Proxy / ResultSet allocations as
the dominant source, this round did not implement a dedicated `ResultSet`.
Instead, it made a conservative cleanup in the `BULK_ADD_ROW` entry that SQL
diagnostics already show as still expensive:

1. `bulkInsertAppendRows` no longer builds a `BulkRowWrite` list and then
   iterates the batch a second time.
2. Each batch resolves `txnId` and `TabId` once, instead of repeating that work
   for each row.
3. The same-batch primary-key `HashSet` is initialized from the expected batch
   size to reduce resize churn.
4. The existing savepoint boundary is preserved: primary-key conflicts, unique
   index conflicts, and secondary-index write failures still roll back the full
   batch.

A new test covers the main semantic risk: if a later row in the same batch has
a duplicate primary key, earlier rows already written by the direct path must
be rolled back.

```powershell
.\gradlew.bat :vexra-adb:test --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest --rerun-tasks
```

Verification result: passed.

Sequential benchmark result:

| workload | threads | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | Result file |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `insert` | 1 | 819.00 | 972 | 2604 | 3600 | 37504 | `vexra-adb/build/adb-benchmark/insert_bulk_direct_write_stage_r3.properties` |
| `mixed` | 8 | 2479.34 | 2242 | 8142 | 11505 | 733347 | `vexra-adb/build/adb-benchmark/jdbc_mixed_bulk_direct_write_stage_r3.properties` |

Conclusion:

- The `mixed` 8-thread result stayed close to the Round 20 measured-window
  result, so the bulk write entry cleanup did not damage the combined path.
- The `insert` p50/p95/p99 values improved clearly versus the r2 sequential
  rerun, which is a positive signal for removing intermediate write objects and
  repeated per-row table lookup. This still needs longer repeated runs before it
  should be treated as a stable headline number.
- The `mixed` `allocation.bytesPerOperation` is still about `733KB/op`, which
  means the removed `BulkRowWrite` objects are not the dominant allocation
  source.
- Until JFR CLI / JMC output proves Proxy / ResultSet allocation dominance, the
  dedicated `ResultSet` work remains intentionally deferred. The next valuable
  step is to export `adb-focus.txt` in a JFR-capable environment or continue
  splitting the `TxnMap2.getVisible` / write-batch internal phases.

## Round 23: Visible Row Internal Phase Breakdown

This round continues the `TxnMap2.getVisible` / visible-row path work. Instead
of guessing the next optimization, it splits `TxnManager.getVisible`, which is
shared by prepared point lookup and primary find, into detail-only phases. In
the default mode, the method only reads a cached per-manager detail flag and
does not perform nanosecond timing or phase recording.

New phases:

| phase | Meaning |
| --- | --- |
| `ADB_VISIBLE_LOCAL_WRITE_CHECK` | Check the current transaction local write-set |
| `ADB_VISIBLE_LOCAL_WRITE_HIT` / `ADB_VISIBLE_LOCAL_WRITE_MISS` | Local write hit / miss |
| `ADB_VISIBLE_ROUTE_POINT_READ` | Region point-read routing boundary |
| `ADB_VISIBLE_COMMITTED_CACHE_HIT` / `ADB_VISIBLE_COMMITTED_CACHE_MISS` | Committed row cache hit / miss |
| `ADB_VISIBLE_COMMITTED_CACHE_VALIDATE` | Underlying committed-version validation when the cache is not trusted |
| `ADB_VISIBLE_STORE_SEEK` | Version-scan cursor seek to the logical-row prefix |
| `ADB_VISIBLE_VERSION_KEY_DECODE` | `VersionKey` decoding |
| `ADB_VISIBLE_INTENT_SKIP` | Skipping an uncommitted intent version |
| `ADB_VISIBLE_ROW_VALUE_DECODE` | `RowValue.decodeValue(...)` |
| `ADB_VISIBLE_STORE_ADVANCE` | Scan advance |
| `ADB_VISIBLE_COMMITTED_STORE_SCAN` | Total committed store scan time |
| `ADB_VISIBLE_READ_SET_RECORD` | Transaction read-set version recording |

The new `preparedPointLookupRecordsVisibleRowDiagnosticBreakdown` test covers:

1. Close and reopen the database, then read a committed row to force a committed
   cache miss, store scan, and row decode.
2. Insert an uncommitted row in the same transaction, then point-read it to
   force a local write hit.
3. Assert that the visible-row breakdown phases are recorded in SQL diagnostics.

Verification command:

```powershell
.\gradlew.bat :vexra-adb:test --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest --rerun-tasks
```

Verification result: passed.

Detail mixed 8-thread reproduction command:

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkDetailedDiagnostics=true -PadbBenchmarkWorkload=mixed -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=8 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_visible_breakdown_detail_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-mixed-visible-breakdown-detail-stage/adb-benchmark
```

Detail mixed 8-thread result:

| workload | threads | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | Result file |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `mixed` detail | 8 | 2454.99 | 2386 | 8439 | 11670 | 733451 | `vexra-adb/build/adb-benchmark/jdbc_mixed_visible_breakdown_detail_stage.properties` |

Key visible-row phases:

| phase | count | avg us | max us |
| --- | ---: | ---: | ---: |
| `ADB_VISIBLE_LOCAL_WRITE_CHECK` | 2359 | 0 | 34 |
| `ADB_VISIBLE_LOCAL_WRITE_MISS` | 2359 | 0 | 34 |
| `ADB_VISIBLE_ROUTE_POINT_READ` | 2359 | 0 | 34 |
| `ADB_VISIBLE_COMMITTED_CACHE_MISS` | 2359 | 0 | 52 |
| `ADB_VISIBLE_STORE_SEEK` | 2359 | 278 | 7402 |
| `ADB_VISIBLE_VERSION_KEY_DECODE` | 2100 | 0 | 45 |
| `ADB_VISIBLE_ROW_VALUE_DECODE` | 2100 | 0 | 25 |
| `ADB_VISIBLE_COMMITTED_STORE_SCAN` | 2359 | 296 | 7490 |
| `ADB_VISIBLE_READ_SET_RECORD` | 2359 | 0 | 36 |
| `ADB_POINT_LOOKUP_VISIBLE_ROW` | 2100 | 240 | 7010 |

Default mixed 8-thread rerun:

| workload | threads | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | Result file |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `mixed` | 8 | 2400.00 | 2446 | 8525 | 11921 | 683624 | `vexra-adb/build/adb-benchmark/jdbc_mixed_visible_breakdown_default_stage.properties` |

Conclusion:

- In the detail mixed run, the visible-row cost is mostly store scan / seek:
  `ADB_VISIBLE_COMMITTED_STORE_SCAN` averages about `296us`, and
  `ADB_VISIBLE_STORE_SEEK` averages about `278us`.
- `ADB_VISIBLE_ROW_VALUE_DECODE` is close to `0us` on average, so point-lookup
  payload decode is not the largest remaining cost.
- The committed cache was a miss for this mixed run. The next more valuable
  direction is therefore committed row cache usefulness, cursor/seek reuse, or a
  more direct store-get path for read-only point lookups.
- The default mixed 8-thread run did not show an obvious semantic or
  performance regression. For item 3, the next optimization should reduce the
  store seek/scan boundary before spending more effort on `RowValue.decodeValue`.

## Round 24: Read-Fill Visible Committed Cache

Round 23 showed that `ADB_VISIBLE_COMMITTED_STORE_SCAN` /
`ADB_VISIBLE_STORE_SEEK` is the main visible-row cost, and the mixed workload
showed committed cache misses only. Inspection confirmed:

1. `RowKey` inherits byte-array `equals/hashCode` from `Key`, so cache-key
   equality is correct.
2. `committedRowCache` was previously refreshed only after commits in the same
   process through `refreshCommittedRowCache(...)`.
3. Benchmark seed data and historical rows after database reopen were not
   filled into the cache after a store scan, so later reads of the same row
   continued to seek/scan.

Changes:

1. `TxnManager.getVisibleCommitted(...)` fills `committedRowCache` after it
   resolves a committed visible row from store.
2. The detail-only `getVisibleCommittedDetailed(...)` does the same when a
   committed visible row is found during store scan.
3. Deleted rows, null rows, and non-row keys are not cached. Cached values copy
   the `rowKey` to avoid sharing mutable `RowValue` state.
4. Existing commit/update/delete `refreshCommittedRowCache(...)` still replaces
   or removes cache entries for the same key.
5. The default `TRUST_COMMITTED_ROW_CACHE=false` validation is preserved, so a
   stale in-memory cache entry is checked against the underlying committed
   version before it is returned.

Test enhancement:

- `preparedPointLookupRecordsVisibleRowDiagnosticBreakdown` now reads the same
  committed row twice. The first read exercises store scan, and the second read
  must record `ADB_VISIBLE_COMMITTED_CACHE_HIT` and
  `ADB_VISIBLE_COMMITTED_CACHE_VALIDATE`.
- The same test continues to cover current-transaction local write hits through
  `ADB_VISIBLE_LOCAL_WRITE_HIT`.

Verification command:

```powershell
.\gradlew.bat :vexra-adb:test --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest --rerun-tasks
```

Verification result: passed.

Detail mixed 8-thread result:

| workload | threads | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | Result file |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `mixed` detail | 8 | 2568.49 | 2327 | 7969 | 10319 | 711405 | `vexra-adb/build/adb-benchmark/jdbc_mixed_visible_cache_fill_detail_stage.properties` |

Key visible-row phase comparison:

| phase | Round 23 count / avg us | Round 24 count / avg us | Notes |
| --- | ---: | ---: | --- |
| `ADB_VISIBLE_COMMITTED_STORE_SCAN` | 2359 / 296 | 2139 / 276 | Read-fill avoided 220 store scans |
| `ADB_VISIBLE_STORE_SEEK` | 2359 / 278 | 2139 / 258 | Seek count and average both dropped |
| `ADB_VISIBLE_COMMITTED_CACHE_HIT` | 0 / - | 220 / 102 | New cache hits; default validation is still included |
| `ADB_POINT_LOOKUP_VISIBLE_ROW` | 2100 / 240 | 2100 / 215 | Point visible-row average dropped |

Default mixed 8-thread rerun:

| workload | threads | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | Result file |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `mixed` | 8 | 2497.92 | 2379 | 8240 | 10566 | 663277 | `vexra-adb/build/adb-benchmark/jdbc_mixed_visible_cache_fill_default_stage.properties` |

Point lookup rerun:

| workload | threads | throughput ops/s | p99 us | Result file |
| --- | ---: | ---: | ---: | --- |
| `point_lookup` | 1 | 1969.80 | 1673 | `vexra-adb/build/adb-benchmark/point_lookup_visible_cache_fill_stage.properties` |

Conclusion:

- Read-filling the committed cache shows a positive mixed-workload signal:
  detail mixed improved from `2454.99 ops/s` to `2568.49 ops/s`, and default
  mixed improved from `2400.00 ops/s` to `2497.92 ops/s`.
- The phase breakdown proves fewer store seek/scan operations and 220 committed
  cache hits.
- The point lookup standalone run did not exceed the best historical result, so
  it is not treated as the headline improvement. It needs repeated runs that
  account for random access pattern and cache-validation cost.
- For item 3, the next useful step is reducing cache-hit validation cost or
  running a controlled trusted-cache benchmark. If work shifts to item 5, the
  same read-fill / dedicated-ResultSet thinking can be applied to the range
  count outer entry.

## Round 25: Controlled Trusted Committed Cache Comparison

Round 24 introduced read-fill cache hits, but the default
`TRUST_COMMITTED_ROW_CACHE=false` mode still validates the underlying committed
version on every cache hit to protect restore scenarios. This round only
measures the upper bound of skipping that validation; it does not change the
default safety policy.

Detail mixed 8-thread trusted reproduction command:

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkDetailedDiagnostics=true -PadbBenchmarkJvmArgs=-Dvexra.adb.rowCache.trustCommitted=true -PadbBenchmarkWorkload=mixed -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=8 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_trusted_visible_cache_detail_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-mixed-trusted-visible-cache-detail-stage/adb-benchmark
```

Default-diagnostics-off trusted mixed 8-thread reproduction command:

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkJvmArgs=-Dvexra.adb.rowCache.trustCommitted=true -PadbBenchmarkWorkload=mixed -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=8 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_trusted_visible_cache_default_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-mixed-trusted-visible-cache-default-stage/adb-benchmark
```

Result comparison:

| workload | mode | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | Result file |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| `mixed` | default safe cache | 2497.92 | 2379 | 8240 | 10566 | 663277 | `vexra-adb/build/adb-benchmark/jdbc_mixed_visible_cache_fill_default_stage.properties` |
| `mixed` | trusted cache | 2700.27 | 2185 | 7807 | 9920 | 663214 | `vexra-adb/build/adb-benchmark/jdbc_mixed_trusted_visible_cache_default_stage.properties` |
| `mixed` detail | default safe cache | 2568.49 | 2327 | 7969 | 10319 | 711405 | `vexra-adb/build/adb-benchmark/jdbc_mixed_visible_cache_fill_detail_stage.properties` |
| `mixed` detail | trusted cache | 2645.50 | 2268 | 7573 | 10503 | 663144 | `vexra-adb/build/adb-benchmark/jdbc_mixed_trusted_visible_cache_detail_stage.properties` |

Trusted detail key phases:

| phase | count | avg us | max us |
| --- | ---: | ---: | ---: |
| `ADB_VISIBLE_COMMITTED_CACHE_MISS` | 2139 | 0 | 46 |
| `ADB_VISIBLE_STORE_SEEK` | 2139 | 237 | 6936 |
| `ADB_VISIBLE_COMMITTED_STORE_SCAN` | 2139 | 256 | 6957 |
| `ADB_VISIBLE_COMMITTED_CACHE_HIT` | 220 | 1 | 22 |
| `ADB_POINT_LOOKUP_VISIBLE_ROW` | 2100 | 184 | 6965 |

Conclusion:

- `-Dvexra.adb.rowCache.trustCommitted=true` has a clear upside: default mixed
  improved from `2497.92 ops/s` to `2700.27 ops/s`, and p99 improved from
  `10566us` to `9920us`.
- Detail phases show trusted cache hits average about `1us`, while Round 24
  default safe cache hits averaged about `102us`; the difference is mainly the
  underlying committed-version validation.
- This mode should not be the default: if restore / checkpoint rollback happens
  in the same process, skipping validation may return stale in-memory cache
  entries. The default remains validation-on.
- To safely make this benefit default, `DbStore.restore(...)` / backup-restore
  runtime and `TxnManager` need cache invalidation or a store generation
  mechanism. Until then, this remains a pure-local benchmark switch for runs
  without restore interference.

## Round 26: Restore Invalidation Boundary for Trusted Committed Cache

Round 25 proved that skipping physical committed-version validation on committed
cache hits has a clear upside, but it cannot become the default as-is. After
restore or snapshot installation, old in-process committed row, row-count, and
rowId hint caches may still point at pre-restore data. This round makes that
risk boundary explicit in code:

1. `TxnManager` now has an instance-level `trustCommittedRowCache` flag. The
   default constructor still reads `-Dvexra.adb.rowCache.trustCommitted=true`,
   while tests can directly construct a trusted manager.
2. `TxnManager.invalidateStoreDerivedCaches()` clears committed row cache,
   row-count cache, and max rowId hints together.
3. `AdbRuntimeOperationsBridge` has a new optional `TxnManager` constructor
   parameter. After a successful runtime restore, it calls
   `invalidateStoreDerivedCaches()`.
4. The existing `AdbRuntimeOperationsBridge(DbStore, AdbControlPlaneClient,
   String)` constructor remains compatible; callers that do not pass a manager
   keep the old behavior.

Test coverage:

- `AdbRuntimeOperationsBridgeTest.shouldRunFullBackupAndRestoreDrill` now uses
  a trusted `TxnManager`: it checkpoints `before-backup`, commits and caches
  `after-backup`, then restores and must read `before-backup`. Without restore
  cache invalidation, this test would return the stale `after-backup` value in
  trusted-cache mode.

Verification command:

```powershell
.\gradlew.bat --% :vexra-adb:test --tests net.xdob.vexra.adb.db.AdbRuntimeOperationsBridgeTest --rerun-tasks
```

Result: passed.

Trusted mixed 8-thread rerun command:

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkJvmArgs=-Dvexra.adb.rowCache.trustCommitted=true -PadbBenchmarkWorkload=mixed -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=8 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_trusted_cache_restore_invalidation_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-mixed-trusted-cache-restore-invalidation-stage/adb-benchmark
```

Result:

| workload | mode | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | Result file |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| `mixed` | trusted cache, restore invalidation code | 2446.98 | 2370 | 8240 | 10900 | 663423 | `vexra-adb/build/adb-benchmark/jdbc_mixed_trusted_cache_restore_invalidation_stage.properties` |

Conclusion:

- This round does not change the point lookup or range count hot path, so the
  single mixed result is not treated as a new throughput improvement.
- `allocation bytes/op` is in the same range as the Round 25 trusted default
  run, so the restore invalidation boundary did not add steady-state allocation
  overhead to the mixed hot path.
- Throughput was lower than the Round 25 trusted default in this single rerun;
  because the new code only clears caches after restore, this is recorded as a
  benchmark-variance risk to rerun rather than direct hot-path regression.
- This moves `trustCommitted` from a pure benchmark switch toward a
  runtime-restore-safe local optimization switch. To make the benefit default,
  the next step is covering region snapshot installer, direct
  `DbStore.restore(...)` callers, and external store-change notification, or
  introducing a store generation mechanism.

## Round 27: Full-Table COUNT(*) singleLong ResultSet

Prepared range count had already been moved to
`AdbSimpleResultSet.singleLong(...)`, but plain `SELECT COUNT(*) FROM table`
still built its fast-path result through `ValueBigint + Value[] +
singleRow(...)`. This round changes `AdbTableCountPlan` to return
`singleLong("COUNT(*)", count)` as well:

1. Remove per-count `ValueBigint` and one-element `Value[]` allocation from the
   table-count fast path.
2. Keep the `COUNT(*)` column name and preserve `findColumn("COUNT(*)")`,
   `getLong(1)`, and `getLong("COUNT(*)")` behavior.
3. Do not change row-count metadata, transaction-local delta, or SQL fallback
   semantics.

Verification command:

```powershell
.\gradlew.bat --% :vexra-adb:test --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest --rerun-tasks
```

Result: passed.

Benchmark:

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkWorkload=table_count -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=1 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/table_count_single_long_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/table-count-single-long-stage/adb-benchmark
```

Result:

| workload | threads | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | Result file |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `table_count` | 1 | 2158.27 | 436 | 661 | 952 | 9149 | `vexra-adb/build/adb-benchmark/table_count_single_long_stage.properties` |
| `table_count` | 8 | 3802.28 | 1842 | 3702 | 4967 | 9467 | `vexra-adb/build/adb-benchmark/jdbc_table_count_single_long_stage.properties` |

Conclusion:

- Single-thread `table_count` improved clearly versus the Round 19 prewarm
  baseline of `1318.68 ops/s`; p99 dropped from `1844us` to `952us`.
- `allocation bytes/op` stayed in the same range as the historical baseline,
  which means this round mainly removes temporary objects at the count result
  boundary, while total allocation is still dominated by JDBC proxy /
  statement / diagnostics overhead.
- This completes part of item 5 by using the dedicated long ResultSet handler
  for the full-table count fast path. The fully dedicated non-proxy ResultSet
  class should still wait for JFR allocation evidence.

## Round 28: Visible Row Raw Scan and Historical-Version Cache Guard

Rounds 24 and 25 introduced committed row read-fill cache into the point lookup
path. While continuing item 3, this round found a boundary that must be
tightened first: when a reader startTs is older than a row's latest committed
version, the store scan skips the new version and returns an older visible
version. That older version must not be written into the global committed row
cache, otherwise a later reader can be misled by stale cache.

Changes:

1. `TxnManager.getVisibleCommitted(...)` now uses an internal raw-key scan for
   row keys, avoiding per-call `DefaultVersionResolver` / `VersionKey`
   allocation in the default path.
2. The raw scan tracks whether it saw a committed version newer than the
   current transaction startTs. It only read-fills committed row cache when the
   returned row is also the latest committed version.
3. The detail diagnostic path keeps its existing phase breakdown, but follows
   the same "do not cache historical versions" rule.
4. `cacheCommittedVisible(...)` no longer lets an older commitTs overwrite a
   newer cached value.
5. Added `TxnManagerVisibleRowFastPathTest`, which writes a direct
   commitTs=10 / commitTs=20 version chain and verifies that a startTs=15 read
   does not pollute a startTs=25 read.

Verification command:

```powershell
.\gradlew.bat --% :vexra-adb:test --tests net.xdob.vexra.adb.db.TxnManagerVisibleRowFastPathTest --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest --rerun-tasks
```

Result: passed.

Benchmarks:

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkWorkload=point_lookup -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=1 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/point_lookup_visible_raw_scan_no_read_object_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/point-lookup-visible-raw-scan-no-read-object-stage/adb-benchmark
```

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkWorkload=mixed -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=8 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_visible_raw_scan_no_read_object_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-mixed-visible-raw-scan-no-read-object-stage/adb-benchmark
```

Results:

| workload | threads | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | Result file |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `point_lookup` | 1 | 1813.78 | 498 | 948 | 1312 | 10704 | `vexra-adb/build/adb-benchmark/point_lookup_visible_raw_scan_no_read_object_stage.properties` |
| `mixed` | 8 | 2228.83 | 2491 | 8839 | 12591 | 711434 | `vexra-adb/build/adb-benchmark/jdbc_mixed_visible_raw_scan_no_read_object_stage.properties` |

Conclusion:

- This round is primarily a safety prerequisite for item 3, not a mixed
  throughput headline. The single mixed result is below the Round 24 default
  safe-cache baseline and needs more reruns / investigation.
- `point_lookup` recovered to an acceptable range after removing the internal
  read-result object, but it still did not beat the best historical sample.
- The positive value is that committed read-fill cache can no longer be polluted
  by historical snapshots. This gives a safer foundation for later latest
  committed cache, trusted cache defaulting, and store generation work to reduce
  store seek cost.

## Round 29: Bulk Append Batch Unique-Check Decision

While continuing item 4, `BULK_ADD_ROW / ADD_ROW` write-entry optimization, this
round found that `bulkInsertAppendRows` still called
`canSkipAppendUniqueCheck(rowKey)` row by row. This round lifts the append
decision to the batch entry for multi-row append inserts:

1. `TxnManager` adds a `TabId + rowId` append-hint check, so a batch can test
   the committed high-water mark without constructing a `RowKey` first for
   every row.
2. `TxnMap2` adds `canSkipAppendUniqueChecks(tabId, minRowId, maxRowId)`.
   After the caller has de-duplicated the batch, the whole batch can skip the
   committed unique scan when the rowId range does not overlap local writes and
   the minimum rowId is above the transaction-local or committed high-water
   mark.
3. `TxnMap2.putEncodedAppend(...)` records the transaction-local append
   high-water after a bulk row write, so later append batches in the same
   transaction can continue to use the fast path.
4. Single-row bulk keeps a dedicated branch, so ordinary
   `INSERT INTO ... VALUES (...)` is not charged with the multi-row
   `HashSet` and two-pass loop.
5. `canSkipAppendUniqueCheck(DataKey)` now checks the same transaction's local
   write for the exact key first, preventing an update/delete followed by an
   insert of the same key from being incorrectly accepted by append high-water.

New tests:

- `rejectsDuplicatePrimaryKeyAcrossBulkBatchesInOneTransaction` covers one
  successful batch followed by a duplicate batch in the same transaction and
  verifies that only the failed batch is rolled back.
- `appendsMultipleBulkBatchesInOneTransaction` covers consecutive append
  batches in one transaction.

Verification command:

```powershell
.\gradlew.bat --% :vexra-adb:test --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest --rerun-tasks
```

Result: passed.

Benchmarks:

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkWorkload=insert -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=1 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_insert_single_branch_bulk_append_batch_skip_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-insert-single-branch-bulk-append-batch-skip-stage/adb-benchmark
```

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkMode=jdbc_bulk -PadbBenchmarkWorkload=insert -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=1 -PadbBenchmarkStatementBatchSize=100 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_bulk_insert_single_branch_bulk_append_batch_skip_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-bulk-insert-single-branch-bulk-append-batch-skip-stage/adb-benchmark
```

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkWorkload=mixed -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=8 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_single_branch_bulk_append_batch_skip_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-mixed-single-branch-bulk-append-batch-skip-stage/adb-benchmark
```

Results:

| workload | mode | threads | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | Result file |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `insert` | `jdbc` | 1 | 802.78 | 1010 | 2449 | 3322 | 38034 | `vexra-adb/build/adb-benchmark/jdbc_insert_single_branch_bulk_append_batch_skip_stage.properties` |
| `insert` | `jdbc_bulk` | 1 | 54545.45 | 18 | 28 | 31 | 2838 | `vexra-adb/build/adb-benchmark/jdbc_bulk_insert_single_branch_bulk_append_batch_skip_stage.properties` |
| `mixed` | `jdbc` | 8 | 2572.90 | 2335 | 7853 | 10089 | 663424 | `vexra-adb/build/adb-benchmark/jdbc_mixed_single_branch_bulk_append_batch_skip_stage.properties` |

Conclusion:

- This round completes part of item 4: merging multi-row insert unique checks
  inside one transaction and expanding the append-only primary-key fast path.
- `jdbc_bulk` benefits directly from the batch entry. The measured window only
  records 30 `ADB_TABLE_BULK_ADD_ROW` operations, and
  `allocation.bytesPerOperation` drops to about `2.8KB/op`, so the direct bulk
  entry remains the highest-value write path today.
- Mixed 8-thread throughput recovered from Round 28's `2228.83 ops/s` to
  `2572.90 ops/s`, and p99 dropped from `12591us` to `10089us`. This indicates
  that the change does not introduce an obvious regression in the mixed path.
- Plain single-row `jdbc insert` is still only `802.78 ops/s`, below the Round
  15 allocation baseline of `1004.35 ops/s`. Since this round already keeps a
  dedicated single-row branch, the next valuable write optimization is not more
  bulk unique-check work; it is breaking down `ADB_COMMIT_WRITE`, write batch,
  and fsync cost, or making more ordinary JDBC scenarios truly aggregate into
  batched commits.
