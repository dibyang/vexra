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

## Round 30: Commit Write Encoding Without RowValue Copy

Round 29 showed that plain single-row `jdbc insert` was still slow, and
`ADB_COMMIT_WRITE` remained a stable visible write-stage cost. This round makes
a narrow low-risk allocation optimization: local commit write batch no longer
calls `copyForCommit(...)` to create a temporary `RowValue` for every written
key. Instead, encoding writes the current `commitTs` directly into the bytes.

Changes:

1. `RowValue.encodeValue(RowValue, long commitTs)` adds an overload that
   overrides the encoded commit timestamp without mutating the source object.
2. `TxnManager.commitLocalDirect(...)` uses this overload when writing local
   committed versions.
3. `RowValueTest.encodeValueWithCommitTsDoesNotMutateSource` covers the
   encoded result and source-object immutability.
4. `refreshCommittedRowCache(...)` still keeps its post-commit cache copy, so
   transaction-local objects are not exposed directly through committed cache.

Verification command:

```powershell
.\gradlew.bat --% :vexra-adb:test --tests net.xdob.vexra.adb.db.RowValueTest --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest --rerun-tasks
```

Result: passed.

Benchmarks:

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkWorkload=insert -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=1 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_insert_commit_encode_override_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-insert-commit-encode-override-stage/adb-benchmark
```

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkWorkload=mixed -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=8 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_commit_encode_override_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-mixed-commit-encode-override-stage/adb-benchmark
```

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkMode=jdbc_bulk -PadbBenchmarkWorkload=insert -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=1 -PadbBenchmarkStatementBatchSize=100 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_bulk_insert_commit_encode_override_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-bulk-insert-commit-encode-override-stage/adb-benchmark
```

Results:

| workload | mode | threads | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | `ADB_COMMIT_WRITE` avg us | Result file |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `insert` | `jdbc` | 1 | 470.29 | 2129 | 3362 | 4412 | 36698 | 19 | `vexra-adb/build/adb-benchmark/jdbc_insert_commit_encode_override_stage.properties` |
| `mixed` | `jdbc` | 8 | 2419.35 | 2379 | 8125 | 11401 | 662984 | 54 | `vexra-adb/build/adb-benchmark/jdbc_mixed_commit_encode_override_stage.properties` |
| `insert` | `jdbc_bulk` | 1 | 68181.82 | 13 | 20 | 20 | 2836 | - | `vexra-adb/build/adb-benchmark/jdbc_bulk_insert_commit_encode_override_stage.properties` |

Conclusion:

- This round removes one temporary `RowValue` copy object per row from the local
  commit write batch, which directly advances the item 1/4 goal of reducing
  commit / write batch objects.
- Plain `jdbc insert` allocation dropped from Round 29's `38034` to `36698`
  bytes/op, but throughput and p99 regressed in this short run, so this is not
  recorded as a throughput win.
- Mixed 8-thread allocation stayed in the same range as Round 29, while
  throughput moved from `2572.90 ops/s` down to `2419.35 ops/s`. The change is
  too small to dominate point lookup, range count, and storage-write variance.
- The next write optimization should target larger structural costs inside
  `ADB_COMMIT_WRITE`: `VersionKey.toBytes()`, `RowValue.encodeValue()`,
  `AdbWriteBatch` entry allocation, and lower-level ldb write batch / fsync,
  rather than only removing one Java copy object.

## Round 31: Direct Commit Row-Key Encoding

Round 30 removed the temporary `RowValue` copy from the local commit write
batch, but each row write still constructed a `VersionRowKey` and then copied
its bytes through `toBytes()`. For append / insert paths dominated by row-key
writes, this is a stable per-row object cost. This round continues shrinking
commit / write batch objects:

1. `VersionRowKey.committedBytes(RowKey, long commitTs)` adds a direct encoder
   that keeps exactly the same on-disk key format as
   `VersionRowKey.of(...).toBytes()`.
2. The encoder writes big-endian fields directly, avoiding `ByteBuffer.wrap(...)`
   and the temporary `VersionRowKey` object.
3. `TxnManager.commitLocalDirect(...)` uses the direct encoder for `RowKey`.
   Index keys still use the existing `VersionKey.of(...).toBytes()` path to keep
   the change narrow.
4. `VersionKeyTest.committedRowBytesMatchVersionRowKeyEncoding` verifies byte
   equality with the old object path and covers the decoded table, rowId, commit
   marker, and `toDataKey()`.

Verification command:

```powershell
.\gradlew.bat --% :vexra-adb:test --tests net.xdob.vexra.adb.key.VersionKeyTest --tests net.xdob.vexra.adb.db.RowValueTest --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest --rerun-tasks
```

Result: passed.

Benchmarks:

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkWorkload=insert -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=1 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_insert_direct_row_version_key_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-insert-direct-row-version-key-stage/adb-benchmark
```

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkWorkload=mixed -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=8 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_direct_row_version_key_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-mixed-direct-row-version-key-stage/adb-benchmark
```

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkMode=jdbc_bulk -PadbBenchmarkWorkload=insert -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=1 -PadbBenchmarkStatementBatchSize=100 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_bulk_insert_direct_row_version_key_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-bulk-insert-direct-row-version-key-stage/adb-benchmark
```

Results:

| workload | mode | threads | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | `ADB_COMMIT_WRITE` avg us | Result file |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `insert` | `jdbc` | 1 | 353.52 | 2903 | 4430 | 5505 | 36404 | 37 | `vexra-adb/build/adb-benchmark/jdbc_insert_direct_row_version_key_stage.properties` |
| `mixed` | `jdbc` | 8 | 1984.13 | 2637 | 9528 | 15355 | 711148 | 55 | `vexra-adb/build/adb-benchmark/jdbc_mixed_direct_row_version_key_stage.properties` |
| `insert` | `jdbc_bulk` | 1 | 62500.00 | 15 | 18 | 24 | 2835 | - | `vexra-adb/build/adb-benchmark/jdbc_bulk_insert_direct_row_version_key_stage.properties` |

Conclusion:

- This round removes more temporary objects from the row-key local commit write
  path: each row no longer needs a `VersionRowKey` object and a `toBytes()`
  defensive copy. This completes another part of item 1/4 around commit / write
  batch key objects.
- Plain `jdbc insert` allocation moved slightly from Round 30's `36698` to
  `36404` bytes/op, but throughput regressed from `470.29 ops/s` to
  `353.52 ops/s`. This should only be recorded as object-path shrinkage, not as
  a throughput win.
- Mixed 8-thread throughput and allocation both regressed in this short run.
  The current benchmark window is dominated by point lookup, range count,
  storage write, and runtime variance; further per-row small-object work has
  low marginal value.
- The next highest-value optimization is no longer hand-removing one key/value
  object at a time. It should either:
  1. capture a parseable JFR / async-profiler sample to confirm whether
     ResultSet / JDBC proxy allocation is still dominant;
  2. if yes, replace high-frequency `AdbSimpleResultSet` dynamic proxies with
     dedicated result set classes;
  3. if no, move to `AdbWriteBatch` / ldb write batch / fsync aggregation, or
     make ordinary JDBC inserts easier to combine into batch commits.

## Round 32: Parseable Mixed 8-Thread JFR and Safe-Cache Verify-Key Optimization

After Round 31, this round returned to item 1: capture full `mixed` 8-thread
JFR evidence before deciding whether to implement dedicated ResultSet classes.
The first step was making JFR profiling repeatable on this machine:

1. `scripts/adb-benchmark-jfr.ps1` adds a `-JavaHome` option and chooses the JFR
   startup flags by JDK version. JDK 8 keeps
   `-XX:+UnlockCommercialFeatures`; JDK 11+ does not use that legacy flag.
2. `scripts/adb-jfr-hotspots.ps1` now falls back to a Java 11+ parser when the
   `jfr` CLI is not installed.
3. `scripts/AdbJfrHotspots.java` uses `jdk.jfr.consumer` to aggregate JFR
   allocation and execution samples into `summary.txt`,
   `allocation-events.txt`, `execution-samples.txt`, and `adb-focus.txt`.
4. `adb-focus.txt` also lists allocated classes under each focus pattern, so a
   stack match such as `AdbPreparedStatementProxy` can be separated from the
   actual allocated ldb objects like `Slice`, `InternalKey`, and `[B`.

JFR capture command:

```powershell
C:\WINDOWS\System32\WindowsPowerShell\v1.0\powershell.exe -ExecutionPolicy Bypass -File .\scripts\adb-benchmark-jfr.ps1 -JavaHome 'C:\Program Files\Java\jdk-11' -Workload mixed -Rows 5000 -WarmupOperations 300 -Operations 3000 -Threads 8 -OutputDir 'vexra-adb/build/adb-benchmark/jfr'
```

JFR hotspot command:

```powershell
C:\WINDOWS\System32\WindowsPowerShell\v1.0\powershell.exe -ExecutionPolicy Bypass -File .\scripts\adb-jfr-hotspots.ps1 -JavaHome 'C:\Program Files\Java\jdk-11' -JfrFile 'vexra-adb\build\adb-benchmark\jfr\adb-mixed-20260622-132245.jfr'
```

JFR result files:

- JFR: `vexra-adb/build/adb-benchmark/jfr/adb-mixed-20260622-132245.jfr`
- Benchmark: `vexra-adb/build/adb-benchmark/jfr/adb-mixed-20260622-132245.properties`
- Hotspots: `vexra-adb/build/adb-benchmark/jfr/hotspots/adb-focus.txt`

Key hotspots:

| focus | allocation bytes | events | Notes |
| --- | ---: | ---: | --- |
| `commit` | 1173480 | 3411 | Mostly `[B`, ldb `Slice`, `InternalKey`, and `BlockEntry` |
| `AdbPreparedStatementProxy` | 174912 | 4388 | Many stack matches, but allocated objects mostly come from the ldb read boundary |
| `TxnMap2.getVisible` | 85336 | 2430 | Mostly ldb `Slice`, `InternalKey`, and `BlockEntry` |
| `RowCodec` | 1624 | 16 | Not a dominant mixed allocation source in this sample |
| `WriteBatch` | 1232 | 28 | Present, but smaller than ldb read-boundary samples |
| `RowValue.decodeValue` | 112 | 2 | Not a dominant mixed allocation source in this sample |
| `AdbSimpleResultSet` | 48 | 2 | Does not justify dedicated ResultSet as the immediate next priority |
| `java.lang.reflect.Proxy` | 24 | 1 | Does not justify dedicated ResultSet as the immediate next priority |

The most useful top allocation frames were:

- `java.util.Arrays.copyOf <- [B`: `492536 bytes / 423 events`
- `net.xdob.vexra.ldb.util.Slice.<init> <- [B`: `145936 bytes / 766 events`
- `net.xdob.vexra.ldb.util.Slice.slice <- Slice`: `41376 bytes / 1293 events`
- `InternalTableIterator.getNextElement <- InternalKey`: `17024 bytes / 532 events`
- `BlockIterator.readEntry <- BlockEntry`: `8424 bytes / 351 events`

Based on this evidence, this round did not implement a dedicated ResultSet.
Instead, it applied one narrow optimization to the default safe committed-cache
validation path:

1. `TxnManager.cachedCommittedVersionExists(...)` now uses
   `VersionRowKey.committedBytes(...)` for `RowKey`.
2. Index keys keep the existing `VersionKey.of(...).toBytes()` path to keep the
   change narrow.
3. `TxnManagerVisibleRowFastPathTest` covers this path through a second read:
   the first read fills the cache from store, and the second read hits the
   default safe cache and verifies that the committed version still exists.

Verification command:

```powershell
.\gradlew.bat --% :vexra-adb:test --tests net.xdob.vexra.adb.db.TxnManagerVisibleRowFastPathTest --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest --rerun-tasks
```

Result: passed.

Plain mixed benchmark:

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkWorkload=mixed -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=8 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_jfr_guided_cache_verify_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-mixed-jfr-guided-cache-verify-stage/adb-benchmark
```

Results:

| workload | mode | threads | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | `ADB_COMMIT_WRITE` avg us | Result file |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `mixed` | `jdbc` | 8 | 1965.92 | 2776 | 9559 | 14346 | 662947 | 70 | `vexra-adb/build/adb-benchmark/jdbc_mixed_jfr_guided_cache_verify_stage.properties` |

Conclusion:

- Item 1, full mixed 8-thread JFR plus allocation hotspot extraction, is now
  reproducible and parseable locally.
- JFR did not prove `AdbSimpleResultSet` / `java.lang.reflect.Proxy` allocation
  dominance, so item 2 should not be the next highest-priority implementation.
- The evidence points more strongly at item 3 and item 4: `TxnMap2.getVisible`
  / ldb `Slice`, `InternalKey`, and `BlockEntry` read-boundary allocation, plus
  commit / write batch related `[B` allocation.
- The safe-cache verify-key change is a small object-path optimization. The
  plain mixed benchmark still regressed to `1965.92 ops/s`, so it is not
  recorded as a throughput win.

## Round 33: Remove Unused prefixEnd From Point-Lookup Visibility Scan

Round 32 JFR showed that `TxnMap2.getVisible` allocation mostly came from ldb
`Slice`, `InternalKey`, `BlockEntry`, and `Arrays.copyOf`. While continuing item
3, this round found that point-lookup visibility reads always open a
`ScanDirection.FORWARD` `VersionScanSource`, and
`LdbVersionEntryCursor.seekToRangeStart(...)` only uses `lowerInclusive` in
forward mode. The `upperExclusive` argument is ignored. Therefore the
`KeyCodec.prefixEnd(prefix)` built by `getVisibleCommittedRow(...)` and the
detailed diagnostics path was an unused `Arrays.copyOf` per visibility scan.

Changes:

1. `TxnManager.getVisibleCommittedRow(...)` passes only the row prefix seek key
   and no longer builds `prefixEnd`.
2. `TxnManager.getVisibleCommittedDetailed(...)` removes the same unused upper
   bound from the detailed path.
3. Both call sites include a short comment explaining that the forward cursor
   does not consume `upperExclusive`, so the allocation is not reintroduced by
   accident.

Verification command:

```powershell
.\gradlew.bat --% :vexra-adb:test --tests net.xdob.vexra.adb.db.TxnManagerVisibleRowFastPathTest --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest --rerun-tasks
```

Result: passed.

Benchmarks:

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkWorkload=point_lookup -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=1 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/point_lookup_visible_no_prefix_end_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/point-lookup-visible-no-prefix-end-stage/adb-benchmark
```

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkWorkload=mixed -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=8 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_visible_no_prefix_end_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-mixed-visible-no-prefix-end-stage/adb-benchmark
```

Results:

| workload | mode | threads | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | Result file |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `point_lookup` | `jdbc` | 1 | 1109.47 | 709 | 1695 | 2948 | 10069 | `vexra-adb/build/adb-benchmark/point_lookup_visible_no_prefix_end_stage.properties` |
| `mixed` | `jdbc` | 8 | 2286.59 | 2510 | 8402 | 12437 | 664586 | `vexra-adb/build/adb-benchmark/jdbc_mixed_visible_no_prefix_end_stage.properties` |

Conclusion:

- This round removes one stable but unused `KeyCodec.prefixEnd(...)` /
  `Arrays.copyOf` from every point-lookup visibility scan.
- Mixed recovered from Round 32's plain rerun at `1965.92 ops/s` to
  `2286.59 ops/s`, but allocation remains around `664KB/op`. This is a small
  read-path shrink, not a root-cause fix.
- The standalone `point_lookup` run did not show a throughput win, so this is
  recorded only as an object-path optimization.
- The next item 3 step should move deeper into the ldb read boundary: reducing
  repeated `SnapshotCursor.key/value` byte[] copies, `Slice.slice`,
  `InternalKey`, and `BlockEntry` allocation. If these cannot be solved in ADB,
  vexra-ldb needs a cursor raw-view / reusable-entry API requirement.

## Round 34: Direct RowKey Scan-Prefix Encoding for Point Lookup

Round 33 removed the unused `prefixEnd` from point-lookup visibility scans, but
`getVisibleCommittedRow(...)` and the detailed path still generated the scan
prefix through `rowKey.toBytes()`. `Key.toBytes()` defensively copies through
`Arrays.copyOf(...)`, and JFR already showed `Arrays.copyOf <- [B` as a frequent
mixed allocation entry. This round continues item 3 by shrinking the row-key
prefix object path in point-lookup visibility reads:

1. `RowKey` adds `versionScanPrefixBytes()`, which directly re-encodes the fixed
   row key layout for version scan prefix use without exposing the internal
   `byte[]`.
2. `RowKey.of(...)` and `RowKey` constructor parsing no longer use
   `ByteBuffer.wrap(...)`; they use direct big-endian encode/decode helpers.
3. `TxnManager.getVisibleCommittedRow(...)` and
   `TxnManager.getVisibleCommittedDetailed(...)` use the dedicated prefix
   encoder for `RowKey`.
4. `VersionKeyTest.rowVersionScanPrefixMatchesRowKeyEncodingAndDefensivelyCopies`
   verifies byte equality with `RowKey.toBytes()` and confirms callers cannot
   mutate the `RowKey` by modifying the returned array.

Verification command:

```powershell
.\gradlew.bat --% :vexra-adb:test --tests net.xdob.vexra.adb.key.VersionKeyTest --tests net.xdob.vexra.adb.db.TxnManagerVisibleRowFastPathTest --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest --rerun-tasks
```

Result: passed.

Benchmarks:

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkWorkload=point_lookup -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=1 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/point_lookup_rowkey_direct_prefix_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/point-lookup-rowkey-direct-prefix-stage/adb-benchmark
```

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkWorkload=mixed -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=8 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_rowkey_direct_prefix_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-mixed-rowkey-direct-prefix-stage/adb-benchmark
```

Results:

| workload | mode | threads | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | Result file |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `point_lookup` | `jdbc` | 1 | 1982.82 | 448 | 893 | 1317 | 9953 | `vexra-adb/build/adb-benchmark/point_lookup_rowkey_direct_prefix_stage.properties` |
| `mixed` | `jdbc` | 8 | 2022.93 | 2660 | 9617 | 13498 | 710831 | `vexra-adb/build/adb-benchmark/jdbc_mixed_rowkey_direct_prefix_stage.properties` |

Conclusion:

- The standalone `point_lookup` run improved clearly from Round 33:
  throughput moved from `1109.47 ops/s` to `1982.82 ops/s`, p99 from `2948us`
  to `1317us`, and allocation from `10069` to `9953 bytes/op`. This suggests
  that shrinking the point-lookup visibility prefix path has real positive
  effect.
- Mixed 8-thread did not improve with it. Throughput fell from Round 33's
  `2286.59 ops/s` to `2022.93 ops/s`, and allocation rose to
  `710831 bytes/op`. The combined workload is still dominated by range count,
  write batch, and ldb read-boundary variance.
- For item 3, the remaining ADB-side `RowKey` / prefix small-object work is now
  close to exhausted. Larger gains likely require ldb cursor raw-view /
  reusable-entry support, or a shift to item 4 write batch / group commit
  aggregation.

## Round 35: Prepared Insert Bulk-Plan Metadata Cache

After Round 34, ADB-side point-lookup visibility small-object work was close to
exhausted. This round moves to item 4, the `BULK_ADD_ROW / ADD_ROW` write entry.
`AdbPreparedInsertPlan` already routes ordinary
`INSERT INTO ... VALUES (?, ?)` and multi-values prepared inserts to
`AdbTable.bulkInsertAppendRows(...)`, but every execution still repeated:

1. resolving the target table from the current schema;
2. calling `table.getColumns()`;
3. resolving each insert column name through `table.getColumn(...)`;
4. creating an `ArrayList` even for a single-row insert.

These are stable metadata costs for repeated prepared insert execution. Changes
in this round:

1. `AdbPreparedInsertPlan` caches the resolved `AdbTable`, target table columns,
   and insert columns.
2. Single-row prepared insert uses `Collections.singletonList(row)`, avoiding an
   `ArrayList` and backing array.
3. Multi-row prepared/literal insert keeps the existing batch-list semantics.
4. `repeatedPreparedSingleValuesInsertReusesBulkPlanMetadata` covers executing
   the same `PreparedStatement` three times and verifies that the path still
   hits `ADB_TABLE_BULK_ADD_ROW` without falling back to `ADB_TABLE_ADD_ROW`.

Verification command:

```powershell
.\gradlew.bat --% :vexra-adb:test --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.preparedSingleValuesInsertUsesAdbDriverBulkPath --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.repeatedPreparedSingleValuesInsertReusesBulkPlanMetadata --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.preparedMultiValuesInsertUsesAdbDriverBulkPath --rerun-tasks
```

Result: passed.

Benchmarks:

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkWorkload=insert -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=1 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_insert_prepared_insert_metadata_cache_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-insert-prepared-insert-metadata-cache-stage/adb-benchmark
```

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkMode=jdbc_bulk -PadbBenchmarkWorkload=insert -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=1 -PadbBenchmarkStatementBatchSize=100 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_bulk_insert_prepared_insert_metadata_cache_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-bulk-insert-prepared-insert-metadata-cache-stage/adb-benchmark
```

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkWorkload=mixed -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=8 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_prepared_insert_metadata_cache_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-mixed-prepared-insert-metadata-cache-stage/adb-benchmark
```

Results:

| workload | mode | threads | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | `ADB_TABLE_BULK_ADD_ROW` avg us | Result file |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `insert` | `jdbc` | 1 | 497.59 | 1592 | 4242 | 6788 | 36879 | 672 | `vexra-adb/build/adb-benchmark/jdbc_insert_prepared_insert_metadata_cache_stage.properties` |
| `insert` | `jdbc_bulk` | 1 | 54545.45 | 15 | 33 | 50 | 2731 | 1766 | `vexra-adb/build/adb-benchmark/jdbc_bulk_insert_prepared_insert_metadata_cache_stage.properties` |
| `mixed` | `jdbc` | 8 | 2083.33 | 2631 | 9073 | 13571 | 664407 | 3283 | `vexra-adb/build/adb-benchmark/jdbc_mixed_prepared_insert_metadata_cache_stage.properties` |

Conclusion:

- This round reduces stable metadata lookup and array allocation in the prepared
  insert fast path without changing table-level bulk write semantics.
- `jdbc_bulk` allocation moved down to `2731 bytes/op`, compared with recent
  historical samples around `2835 bytes/op`, indicating that metadata caching
  has allocation value for batch prepared insert.
- Plain `jdbc insert` recovered to `497.59 ops/s`, but p99 remained high; mixed
  stayed near `2k ops/s`, so write-entry metadata is not the only combined
  workload bottleneck.
- The next item 4 step should move from prepared-plan metadata caching to larger
  commit costs: statement-level batching, transaction-local group commit, or
  `AdbWriteBatch` / ldb write batch aggregation.

## Round 36: Prepared Count Fast-Path Session Cache

This round returns to item 5, the range count outer entry. `AdbPreparedRangeCountPlan`
already cached the resolved table and row prefix, and `AdbTableCountPlan` already
cached the resolved table. However, every prepared count execution still called
`connection.unwrap(JdbcConnection.class).getSession()` to obtain the H2
`SessionLocal`. A PreparedStatement plan is bound to one JDBC connection, so the
session can be cached safely and the count fast path can avoid repeated unwrap /
type-check overhead.

Changes:

1. `AdbPreparedRangeCountPlan` caches `SessionLocal` for repeated execution of
   the same prepared range count.
2. `AdbTableCountPlan` caches `SessionLocal` for prepared table count.
3. `repeatedPreparedRangeCountReusesPlanSession` verifies that the same range
   count `PreparedStatement` can be re-executed with different parameters and
   still hit the fast path.
4. `repeatedPreparedTableCountReusesPlanSession` verifies that the same table
   count `PreparedStatement` sees a newly inserted row on later execution.

Verification command:

```powershell
.\gradlew.bat --% :vexra-adb:test --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.repeatedPreparedRangeCountReusesPlanSession --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.repeatedPreparedTableCountReusesPlanSession --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.preparedRangeCountSeesLocalInsertDeleteAndRollback --rerun-tasks
```

Result: passed.

Benchmarks:

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkWorkload=range_scan -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=1 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/range_count_prepared_session_cache_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/range-count-prepared-session-cache-stage/adb-benchmark
```

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkWorkload=table_count -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=1 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/table_count_prepared_session_cache_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/table-count-prepared-session-cache-stage/adb-benchmark
```

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkWorkload=mixed -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=8 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_prepared_count_session_cache_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-mixed-prepared-count-session-cache-stage/adb-benchmark
```

Results:

| workload | mode | threads | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | fast path avg us | Result file |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `range_scan` | `jdbc` | 1 | 779.42 | 1100 | 2379 | 3861 | 502543 | 1270 | `vexra-adb/build/adb-benchmark/range_count_prepared_session_cache_stage.properties` |
| `table_count` | `jdbc` | 1 | 1292.55 | 533 | 1782 | 3913 | 9489 | 767 | `vexra-adb/build/adb-benchmark/table_count_prepared_session_cache_stage.properties` |
| `mixed` | `jdbc` | 8 | 2304.15 | 2537 | 8729 | 12078 | 711170 | 2698 (`ADB_TABLE_RANGE_COUNT_FAST`) | `vexra-adb/build/adb-benchmark/jdbc_mixed_prepared_count_session_cache_stage.properties` |

Conclusion:

- This round removes repeated `Connection.unwrap(...)` and session type checks
  from the prepared count fast path. It keeps using the same H2 session, so
  transaction visibility semantics are unchanged.
- Mixed 8-thread recovered from Round 35's `2083.33 ops/s` to `2304.15 ops/s`,
  and p99 moved from `13571us` to `12078us`, but allocation remains around
  `711KB/op`; this is not the primary allocation source.
- Standalone `range_scan` and `table_count` did not produce a throughput win, so
  the session cache is recorded as a small outer-boundary shrink.
- Further item 5 work needs a larger mechanism such as segment/block-level count
  or ldb-level count metadata, rather than continuing to shave a few JDBC outer
  objects.

## Round 37: Prepared Point-Lookup and Write Fast-Path Session Cache

Round 36 verified that prepared count plans can safely cache `SessionLocal`.
This round applies the same strategy to two high-frequency entries:

1. `AdbPreparedPointLookupPlan` caches the `SessionLocal` bound to the current
   `PreparedStatement` connection, avoiding repeated
   `connection.unwrap(JdbcConnection.class).getSession()` on point lookup.
2. `AdbPreparedInsertPlan` caches the `SessionLocal` for repeated prepared
   insert execution; literal insert plans are short-lived but share the same
   implementation.
3. `repeatedPreparedPointLookupReusesPlanSession` verifies that re-executing the
   same point-lookup `PreparedStatement` with different parameters still returns
   the correct rows and hits `ADB_TABLE_POINT_LOOKUP_FAST`.
4. `repeatedPreparedSingleValuesInsertReusesBulkPlanMetadata` continues to
   verify that repeated prepared insert execution hits `ADB_TABLE_BULK_ADD_ROW`.

Verification command:

```powershell
.\gradlew.bat --% :vexra-adb:test --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.repeatedPreparedPointLookupReusesPlanSession --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.repeatedPreparedSingleValuesInsertReusesBulkPlanMetadata --rerun-tasks
```

Result: passed.

Benchmarks:

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkWorkload=point_lookup -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=1 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/point_lookup_prepared_session_cache_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/point-lookup-prepared-session-cache-stage/adb-benchmark
```

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkWorkload=insert -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=1 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_insert_prepared_session_cache_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-insert-prepared-session-cache-stage/adb-benchmark
```

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkWorkload=mixed -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=8 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_prepared_session_cache_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-mixed-prepared-session-cache-stage/adb-benchmark
```

Results:

| workload | mode | threads | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | fast path avg us | Result file |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `point_lookup` | `jdbc` | 1 | 818.33 | 1011 | 2567 | 8035 | 9995 | 1206 (`ADB_TABLE_POINT_LOOKUP_FAST`) | `vexra-adb/build/adb-benchmark/point_lookup_prepared_session_cache_stage.properties` |
| `insert` | `jdbc` | 1 | 659.78 | 1374 | 2675 | 3600 | 36230 | 485 (`ADB_TABLE_BULK_ADD_ROW`) | `vexra-adb/build/adb-benchmark/jdbc_insert_prepared_session_cache_stage.properties` |
| `mixed` | `jdbc` | 8 | 2354.79 | 2498 | 8594 | 12967 | 662798 | 3153 (`ADB_TABLE_BULK_ADD_ROW`) | `vexra-adb/build/adb-benchmark/jdbc_mixed_prepared_session_cache_stage.properties` |

Conclusion:

- Standalone `insert` improved from Round 35's `497.59 ops/s` to
  `659.78 ops/s`, and p99 moved from `6788us` to `3600us`. The prepared insert
  outer session cache has real value for the write entry.
- Mixed 8-thread throughput moved slightly from Round 36's `2304.15 ops/s` to
  `2354.79 ops/s`, and allocation moved from `711170` to
  `662798 bytes/op`; p99 fluctuated from `12078us` to `12967us`.
- Standalone `point_lookup` was lower than Round 34, with allocation still near
  `10KB/op`. Its remaining bottleneck is unlikely to be `Connection.unwrap(...)`
  and is more likely in the ldb read boundary, version scan, or result-set object
  path.
- At this point the ADB-side prepared-plan outer session/table/column metadata
  caching work is mostly complete. The higher-value next optimizations are:
  1. ldb cursor raw-view / reusable-entry to reduce key/value copies in range
     count and point lookup;
  2. write-side group commit / write batch aggregation to reduce ordinary JDBC
     insert commit cost;
  3. segment/block-level count metadata to avoid visibility scans over many
     entries during range count.

## Round 38: ADB vs h2db Default Table Engine

To measure the current gain over the h2db default table engine, this round adds a
benchmark switch: `--tableEngine adb|h2` /
`-PadbBenchmarkTableEngine=adb|h2`.

1. `tableEngine=adb` keeps the existing `ENGINE "adb_table"` schema.
2. `tableEngine=h2` uses ordinary `CREATE TABLE`, so the same JDBC SQL and
   workload run against the h2db default table engine.
3. The comparison disables ADB SQL diagnostics
   (`-PadbBenchmarkSqlDiagnostics=false`) to avoid observer overhead in the ADB
   result.
4. `shouldRunPointLookupBenchmarkAgainstH2TableEngine` verifies that the H2
   baseline path runs and does not produce ADB SQL diagnostic counts.

Verification command:

```powershell
.\gradlew.bat --% :vexra-adb:test --tests net.xdob.vexra.adb.AdbBenchmarkMainTest.shouldRunPointLookupBenchmarkAgainstH2TableEngine --tests net.xdob.vexra.adb.AdbBenchmarkMainTest.shouldRunMixedBenchmarkAgainstLdbUrl --rerun-tasks
```

Result: passed.

Benchmark profile:

- File-backed databases, no mem mode.
- `rows=5000`, `warmupOperations=300`, `operations=3000`.
- `insert`, `point_lookup`, `table_count`, and `range_scan` use one thread.
- `mixed` uses eight threads with the existing mix: 10% insert, 70% point lookup,
  and 20% range count.
- `range_scan` still executes `SELECT COUNT(*) ... BETWEEN ? AND ?`; this round
  keeps the historical workload name.

Results:

| workload | ADB throughput ops/s | H2 throughput ops/s | ADB/H2 throughput | ADB p99 us | H2 p99 us | p99 improvement | ADB bytes/op | H2 bytes/op |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `insert` | 759.88 | 421.47 | 1.80x | 3195 | 6395 | 2.00x | 34163 | 35374 |
| `point_lookup` | 1717.23 | 264.62 | 6.49x | 1427 | 6654 | 4.66x | 9355 | 34232 |
| `table_count` | 1870.32 | 354.90 | 5.27x | 1390 | 6406 | 4.61x | 8802 | 33182 |
| `range_scan` | 1685.39 | 542.79 | 3.11x | 1660 | 3939 | 2.37x | 501836 | 35651 |
| `mixed` | 2201.03 | 842.70 | 2.61x | 13009 | 15300 | 1.18x | 661824 | 34487 |

Result files:

- `vexra-adb/build/adb-benchmark/compare_adb_insert.properties`
- `vexra-adb/build/adb-benchmark/compare_h2_insert.properties`
- `vexra-adb/build/adb-benchmark/compare_adb_point_lookup.properties`
- `vexra-adb/build/adb-benchmark/compare_h2_point_lookup.properties`
- `vexra-adb/build/adb-benchmark/compare_adb_table_count.properties`
- `vexra-adb/build/adb-benchmark/compare_h2_table_count.properties`
- `vexra-adb/build/adb-benchmark/compare_adb_range_scan.properties`
- `vexra-adb/build/adb-benchmark/compare_h2_range_scan.properties`
- `vexra-adb/build/adb-benchmark/compare_adb_mixed.properties`
- `vexra-adb/build/adb-benchmark/compare_h2_mixed.properties`

Conclusion:

- In this small file-backed benchmark, ADB has higher throughput than the h2db
  default table engine across all measured workloads: about `1.8x` for insert,
  `6.49x` for point lookup, `5.27x` for table count, `3.11x` for range scan,
  and `2.61x` for 8-thread mixed.
- p99 latency is also better for all measured workloads. Point lookup and table
  count improve the most, which confirms that the prepared fast paths, row-count
  metadata, and primary-key lookup work are paying off.
- However, `range_scan` and `mixed` allocation is much higher than H2:
  about `502KB/op` and `662KB/op` for ADB versus about `36KB/op` and `34KB/op`
  for H2. ADB is faster here because of specialized paths and LDB behavior, but
  its object allocation profile is still not healthy.
- The next most valuable optimization remains ldb cursor raw-view /
  reusable-entry plus segment/block-level range count metadata. Without those,
  mixed throughput can beat H2, but GC and memory pressure will limit production
  readiness.

## Round 39: Range-Count Raw-Key Reuse and Mixed JFR Recheck

Round 38 showed that ADB throughput is now higher than the h2db default table
engine across the measured workloads, but `range_scan` and `mixed` allocation
remain high. This round continues items 3 and 5 by removing one repeated ADB-side
key read in the no-local-write range-count raw path:

1. `TxnManager.countVisibleRowsWithoutLocalWrites(...)` no longer calls
   `scan.key()` from the `while` condition. Each loop reads the current raw key
   once and then checks the table prefix.
2. `resolveVisibleCountableInCurrentRawLogicalRow(...)` reuses the first raw key
   already read by the outer loop, so entering visible-row resolution does not
   read the same cursor position again.
3. This does not change MVCC visibility, committed checks, `startTs` comparison,
   delete/payload checks, or scan advance semantics. It only removes a repeated
   key-boundary call at the same cursor position.

Verification command:

```powershell
.\gradlew.bat --% :vexra-adb:test --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.preparedPrimaryKeyRangeCountUsesAdbDriverFastPath --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.preparedRangeCountSeesLocalInsertDeleteAndRollback --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.repeatedPreparedRangeCountReusesPlanSession --rerun-tasks
```

Result: passed.

Benchmarks:

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkWorkload=range_scan -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkRangeSize=32 -PadbBenchmarkThreads=1 -PadbBenchmarkSqlDiagnostics=false -PadbBenchmarkTableEngine=adb -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/range_count_raw_key_reuse_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/range-count-raw-key-reuse-stage/adb-benchmark;DB_CLOSE_DELAY=0
```

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkWorkload=mixed -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkRangeSize=32 -PadbBenchmarkThreads=8 -PadbBenchmarkSqlDiagnostics=false -PadbBenchmarkTableEngine=adb -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_raw_key_reuse_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-mixed-raw-key-reuse-stage/adb-benchmark;DB_CLOSE_DELAY=0
```

Results:

| workload | threads | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | Result file |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `range_scan` | 1 | 815.22 | 1190 | 1941 | 2931 | 502091 | `vexra-adb/build/adb-benchmark/range_count_raw_key_reuse_stage.properties` |
| `mixed` | 8 | 2475.25 | 2331 | 7826 | 10501 | 662152 | `vexra-adb/build/adb-benchmark/jdbc_mixed_raw_key_reuse_stage.properties` |

JFR recheck:

The first run used the Java 8 executable on the default PATH. That produced an
old JFR `version 0.9` file that `jdk.jfr.consumer` cannot read. The full mixed
8-thread JFR was then rerun with `C:\Program Files\Java\jdk-11` and analyzed
with `scripts/adb-jfr-hotspots.ps1`:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\adb-benchmark-jfr.ps1 -JavaHome 'C:\Program Files\Java\jdk-11' -Workload mixed -Rows 5000 -WarmupOperations 300 -Operations 3000 -Threads 8 -OutputDir vexra-adb/build/adb-benchmark/jfr/raw-key-reuse-jdk11
```

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\adb-jfr-hotspots.ps1 -JfrFile D:\work\java2\vexra\vexra-adb\build\adb-benchmark\jfr\raw-key-reuse-jdk11\adb-mixed-20260622-144900.jfr -OutputDir vexra-adb\build\adb-benchmark\jfr\raw-key-reuse-jdk11\hotspots
```

JFR files and reports:

- `vexra-adb/build/adb-benchmark/jfr/raw-key-reuse-jdk11/adb-mixed-20260622-144900.jfr`
- `vexra-adb/build/adb-benchmark/jfr/raw-key-reuse-jdk11/hotspots/allocation-events.txt`
- `vexra-adb/build/adb-benchmark/jfr/raw-key-reuse-jdk11/hotspots/adb-focus.txt`

JFR allocation top classes:

| class | bytes | events |
| --- | ---: | ---: |
| `[B` | 3175936 | 1583 |
| `net.xdob.vexra.ldb.util.Slice` | 56352 | 1761 |
| `[Ljava.lang.String;` | 42576 | 3 |
| `net.xdob.vexra.ldb.impl.InternalKey` | 17280 | 540 |
| `net.xdob.vexra.ldb.table.BlockEntry` | 8592 | 358 |
| `com.google.common.collect.ImmutableEntry` | 8376 | 349 |

JFR top allocation frames:

| frame | bytes | events |
| --- | ---: | ---: |
| `java.nio.HeapByteBuffer.<init> <- [B` | 2097184 | 2 |
| `java.util.Arrays.copyOf <- [B` | 889368 | 472 |
| `net.xdob.vexra.ldb.util.Slice.<init> <- [B` | 113368 | 780 |
| `net.xdob.vexra.ldb.util.Slice.slice <- net.xdob.vexra.ldb.util.Slice` | 42400 | 1325 |
| `net.xdob.vexra.ldb.util.InternalTableIterator.getNextElement <- InternalKey` | 17216 | 538 |
| `net.xdob.vexra.ldb.table.BlockIterator.readEntry <- BlockEntry` | 8592 | 358 |
| `com.google.common.collect.Maps.immutableEntry <- ImmutableEntry` | 8376 | 349 |

ADB focus:

| focus | bytes | events |
| --- | ---: | ---: |
| `commit` | 1181640 | 3467 |
| `AdbPreparedStatementProxy` | 322144 | 4475 |
| `AdbSimpleResultSet` | 138480 | 6 |
| `java.lang.reflect.Proxy` | 138480 | 6 |
| `TxnMap2.getVisible` | 90528 | 2460 |
| `WriteBatch` | 1456 | 32 |
| `RowCodec` | 696 | 7 |
| `RowValue.decodeValue` | 48 | 1 |

Conclusion:

- Raw-key reuse produced a good mixed sample (`2475.25 ops/s`, p99 `10501us`),
  but `range_scan` allocation remains around `502KB/op` and `mixed` remains
  around `662KB/op`. This does not prove the repeated key read was the dominant
  allocation source.
- The current JFR proves that `AdbSimpleResultSet` / `java.lang.reflect.Proxy`
  are not the allocation hotspot: each appears at only `138480 bytes / 6 events`.
  Therefore item 2, a dedicated ResultSet, should stay deferred for now.
- Allocation is concentrated in ldb-layer objects and byte arrays: `[B`,
  `Slice`, `InternalKey`, `BlockEntry`, `ImmutableEntry`, plus
  `Arrays.copyOf`, `Slice.slice`, and `InternalTableIterator.getNextElement`.
- The next highest-value work is to add a cursor raw-view / reusable-entry API in
  `vexra-ldb`, or add ADB segment/block-level count metadata so range count can
  avoid reading so many cursor entries.

## Round 40: Reproducible JFR Benchmark Script

Round 39 exposed a tooling problem in the JFR workflow. If the default PATH uses
Java 8, `scripts/adb-benchmark-jfr.ps1` generates an old Java 8 `version 0.9`
JFR file, and the `jdk.jfr.consumer` path used by
`scripts/adb-jfr-hotspots.ps1` cannot read it. This does not affect ADB runtime
performance, but it makes item 1, JFR-first allocation hotspot analysis, less
reproducible.

This round enhances `scripts/adb-benchmark-jfr.ps1`:

1. When `-JavaHome` is not provided, the script now prefers a JDK 11+ capable of
   generating a modern JFR: `JAVA_HOME`, `C:\Program Files\Java\latest`,
   `jdk-21`, `jdk-17`, `jdk-11`, then other JDK directories under
   `C:\Program Files\Java`.
2. When `-JavaHome` is provided, the script still respects the caller's choice.
   If the Java major version is below 11, it emits a warning that the generated
   JFR may not be parseable by the hotspot script.
3. The script now also accepts and forwards `-RangeSize`, `-TableEngine`, and
   `-SqlDiagnostics`, making ADB/H2 comparisons, diagnostics-off runs, and
   range-count width changes reproducible through the same JFR entry point.
4. The script output includes `Java major`, so benchmark evidence records the
   JFR generation environment directly.

Smoke command:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\adb-benchmark-jfr.ps1 -Workload point_lookup -Rows 20 -WarmupOperations 1 -Operations 2 -Threads 1 -OutputDir vexra-adb/build/adb-benchmark/jfr/script-smoke -SqlDiagnostics false
```

Result: passed. The script automatically selected:

```text
Java: C:\Program Files\Java\jdk-11\bin\java.exe
Java version: java version "11" 2018-09-25
Java major: 11
JFR: vexra-adb/build/adb-benchmark/jfr/script-smoke/adb-point_lookup-20260622-150304.jfr
```

Hotspot parsing command:

```powershell
$latest = Get-ChildItem -LiteralPath vexra-adb\build\adb-benchmark\jfr\script-smoke -Filter *.jfr | Sort-Object LastWriteTime -Descending | Select-Object -First 1
powershell -ExecutionPolicy Bypass -File .\scripts\adb-jfr-hotspots.ps1 -JfrFile $latest.FullName -OutputDir vexra-adb\build\adb-benchmark\jfr\script-smoke\hotspots
```

Result: passed, producing:

- `vexra-adb/build/adb-benchmark/jfr/script-smoke/hotspots/summary.txt`
- `vexra-adb/build/adb-benchmark/jfr/script-smoke/hotspots/allocation-events.txt`
- `vexra-adb/build/adb-benchmark/jfr/script-smoke/hotspots/execution-samples.txt`
- `vexra-adb/build/adb-benchmark/jfr/script-smoke/hotspots/adb-focus.txt`

Conclusion:

- The JFR entry point for item 1 is now more reliable. Future full mixed
  8-thread rechecks should generate a JDK 11+ parseable JFR by default.
- This round does not change the ADB read/write path and should not be counted
  as a throughput optimization. It reduces the observation cost for the next
  iterations on `TxnMap2.getVisible`, commit/write-batch paths, and the outer
  range-count entry.
- Item 2, a dedicated ResultSet, remains deferred because the Round 39 full
  mixed JFR did not prove that `AdbSimpleResultSet` / `java.lang.reflect.Proxy`
  are allocation hotspots.

## Round 41: Raw commitTs Fast Path for Point-Lookup Visibility Scan

Round 39 still showed visible-read cost around `TxnMap2.getVisible` /
`DefaultVisibleRowResolver`, although allocation was not dominated by
`RowValue.decodeValue`. This round handles one low-risk subpath: when a row
point lookup scans multiple committed versions of the same logical row, it now
restores commitTs directly from the `VersionRowKey` raw key. If that version is
newer than the current transaction `startTs`, the scan advances without decoding
the value or copying payload bytes.

Implementation boundary:

1. Added `RAW_VERSION_OFFSET` and `rawCommitTs(byte[])`, using the existing
   fixed `VersionRowKey` layout: `table header(13) + rowId(8) + committed(1) +
   version(8)`.
2. `getVisibleCommittedRow(...)` now checks raw commitTs after confirming the
   raw key is a committed row version. Versions newer than the snapshot no
   longer call `RowValue.decodeValue(...)`.
3. The change only affects the row point-lookup path when detailed diagnostics
   are disabled. It does not change index keys, range count, local write-set
   handling, read-set recording, or committed row cache semantics.
4. Delete-version visibility is unchanged: a delete newer than the snapshot is
   skipped so the older version remains visible, while a delete visible to the
   snapshot hides the row.

Verification command:

```powershell
.\gradlew.bat --% :vexra-adb:test --tests net.xdob.vexra.adb.db.TxnManagerVisibleRowFastPathTest --rerun-tasks
```

Result: passed.

New test:

- `shouldKeepSnapshotVisibleWhenNewerDeleteVersionExists` covers skipping a
  delete version newer than the snapshot, keeping the older value visible, and
  letting a newer snapshot observe the delete.

Benchmarks:

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkWorkload=point_lookup -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=1 -PadbBenchmarkSqlDiagnostics=false -PadbBenchmarkTableEngine=adb -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/point_lookup_raw_commit_ts_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/point-lookup-raw-commit-ts-stage/adb-benchmark;DB_CLOSE_DELAY=0
```

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkWorkload=mixed -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkRangeSize=32 -PadbBenchmarkThreads=8 -PadbBenchmarkSqlDiagnostics=false -PadbBenchmarkTableEngine=adb -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_raw_commit_ts_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-mixed-raw-commit-ts-stage/adb-benchmark;DB_CLOSE_DELAY=0
```

Results:

| workload | threads | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | Result file |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `point_lookup` | 1 | 1977.59 | 457 | 830 | 1258 | 9595 | `vexra-adb/build/adb-benchmark/point_lookup_raw_commit_ts_stage.properties` |
| `mixed` | 8 | 2463.05 | 2346 | 8199 | 11499 | 662332 | `vexra-adb/build/adb-benchmark/jdbc_mixed_raw_commit_ts_stage.properties` |

Conclusion:

- The point-lookup sample reached `1977.59 ops/s` with p99 `1258us`, but
  allocation remains around `9.6KB/op`. This confirms that the change removes
  unnecessary value decoding when skipping newer versions, but it is not the
  primary allocation source.
- Mixed remains around `2463 ops/s` and `662KB/op`, close to the Round 39
  raw-key-reuse sample. The overall allocation bottleneck is still at the ldb
  cursor/key/value boundary and range-count scan.
- The next ADB-internal optimization should focus on per-row key/index
  construction and uniqueness checks in `BULK_ADD_ROW / ADD_ROW`. To materially
  reduce `range_scan/mixed` allocation, ADB still needs a `vexra-ldb` raw-view /
  reusable-entry API or ADB segment/block-level count metadata.

## Round 42: Lazy Deduplication and Batched High-Water Update for Bulk Append

This round continues item 4, write-entry optimization, focusing on the
append-only primary-key path in `BULK_ADD_ROW`. Previously,
`bulkInsertAppendRows(...)` always allocated a `HashSet<Long>` for duplicate
checks in multi-row batches, even when benchmark and common auto-increment /
append workloads naturally produce strictly increasing rowIds. Even after the
whole batch was proven append-safe, each row still called `putEncodedAppend(...)`
and updated the transaction-local append high-water separately.

Changes:

1. The first multi-row bulk insert pass now runs `prepareBulkRow(...)`, computes
   `minRowId/maxRowId`, and detects whether rowIds are strictly increasing.
2. Strictly increasing batches are treated as duplicate-free without allocating
   `HashSet<Long>`. Only non-monotonic or duplicate batches fall back to
   `assertNoDuplicateBulkRowIds(...)`.
3. When `canSkipAppendUniqueChecks(...)` proves the whole batch append-safe,
   each row only writes into the transaction-local write set. The batch advances
   `appendHighWater` once with the maximum rowId after all rows are registered.
4. Savepoint rollback still clears `appendHighWater`, so secondary-index failure
   or later exceptions do not leave a stale hint behind.

Verification command:

```powershell
.\gradlew.bat --% :vexra-adb:test --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.rejectsDuplicatePrimaryKeyThroughBulkInsertPath --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.rollsBackEarlierBulkRowsWhenSameBatchContainsDuplicatePrimaryKey --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.acceptsNonMonotonicUniqueBulkPrimaryKeys --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.appendsMultipleBulkBatchesInOneTransaction --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.bulkInsertsRowsAndSecondaryIndexEntries --rerun-tasks
```

Result: passed.

New test:

- `acceptsNonMonotonicUniqueBulkPrimaryKeys` covers non-strictly-increasing but
  duplicate-free rowIds, making sure lazy deduplication does not reject ordinary
  SQL multi-values inserts.

Benchmarks:

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkMode=jdbc_bulk -PadbBenchmarkWorkload=insert -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=1 -PadbBenchmarkStatementBatchSize=100 -PadbBenchmarkSqlDiagnostics=false -PadbBenchmarkTableEngine=adb -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_bulk_insert_lazy_dedup_highwater_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-bulk-insert-lazy-dedup-highwater-stage/adb-benchmark;DB_CLOSE_DELAY=0
```

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkMode=jdbc -PadbBenchmarkWorkload=insert -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=1 -PadbBenchmarkStatementBatchSize=100 -PadbBenchmarkTransactionBatchSize=100 -PadbBenchmarkSqlDiagnostics=false -PadbBenchmarkTableEngine=adb -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_insert_lazy_dedup_highwater_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-insert-lazy-dedup-highwater-stage/adb-benchmark;DB_CLOSE_DELAY=0
```

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkWorkload=mixed -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkRangeSize=32 -PadbBenchmarkThreads=8 -PadbBenchmarkSqlDiagnostics=false -PadbBenchmarkTableEngine=adb -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_lazy_dedup_highwater_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-mixed-lazy-dedup-highwater-stage/adb-benchmark;DB_CLOSE_DELAY=0
```

Results:

| workload | mode | threads | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | Result file |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `insert` | `jdbc_bulk` | 1 | 93750.00 | 8 | 21 | 21 | 2560 | `vexra-adb/build/adb-benchmark/jdbc_bulk_insert_lazy_dedup_highwater_stage.properties` |
| `insert` | `jdbc` | 1 | 27272.73 | 32 | 77 | 92 | 3505 | `vexra-adb/build/adb-benchmark/jdbc_insert_lazy_dedup_highwater_stage.properties` |
| `mixed` | `jdbc` | 8 | 2223.87 | 2583 | 9437 | 13069 | 710705 | `vexra-adb/build/adb-benchmark/jdbc_mixed_lazy_dedup_highwater_stage.properties` |

Conclusion:

- The `jdbc_bulk` write path benefits clearly: throughput reached
  `93750 ops/s`, and allocation decreased from the Round 37 `2731 bytes/op` to
  `2560 bytes/op`.
- The ordinary JDBC multi-values + transaction-batch sample reached
  `27272.73 ops/s` with p99 `92us`, confirming that this also supports the goal
  of keeping `INSERT INTO ... VALUES (...), (...)` on the bulk API.
- Mixed did not improve in this sample: `2223.87 ops/s` and `710KB/op` is a
  fluctuation down. Mixed only has a small insert share, and allocation is still
  dominated by range count / ldb cursor scanning.
- Item 4 still has follow-up room: secondary-index bulk key construction,
  single-row insert commit/write-batch grouping, and a deeper h2db Insert batch
  callback are larger remaining write-entry opportunities.

## Round 43: Raw commitTs Skip for Newer Range-Count Versions

This round continues item 5, range-count outer / scan-entry optimization. After
Round 39, the range-count raw path already avoids generic
`VersionKey.fromBytes(...)` and payload decoding. However, when one logical row
has multiple committed versions, it still read the value and created
`RowValue.Metadata` before checking `metadata.commitTs <= startTs`. This is the
same shape as the Round 41 point-lookup issue: newer versions can be identified
from the `VersionRowKey` raw key and skipped before reading the value.

Changes:

1. `resolveVisibleCountableInCurrentRawLogicalRow(...)` now restores commitTs
   through the existing `rawCommitTs(...)` after confirming the raw key is a
   committed row version.
2. If commitTs is newer than the current transaction `startTs`, the scan simply
   advances to the next version of the same logical row. It does not call
   `scan.value()` and does not allocate `RowValue.Metadata`.
3. The change only affects the no-local-write raw range-count fast path. The
   local-write path keeps its existing `rowsCoveredByStoreScan` and local-delta
   semantics unchanged.

Verification commands:

```powershell
.\gradlew.bat --% :vexra-adb:test --tests net.xdob.vexra.adb.db.TxnManagerVisibleRowFastPathTest --rerun-tasks
```

```powershell
.\gradlew.bat --% :vexra-adb:test --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.preparedPrimaryKeyRangeCountUsesAdbDriverFastPath --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.preparedRangeCountSeesLocalInsertDeleteAndRollback --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.repeatedPreparedRangeCountReusesPlanSession --rerun-tasks
```

Result: passed.

New test:

- `shouldKeepSnapshotRangeCountWhenNewerVersionsExist` covers range count on an
  old snapshot with newer update/delete versions, and verifies that a newer
  snapshot observes the delete in the count.

Benchmarks:

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkWorkload=range_scan -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkRangeSize=32 -PadbBenchmarkThreads=1 -PadbBenchmarkSqlDiagnostics=false -PadbBenchmarkTableEngine=adb -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/range_count_raw_commit_ts_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/range-count-raw-commit-ts-stage/adb-benchmark;DB_CLOSE_DELAY=0
```

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkWorkload=mixed -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkRangeSize=32 -PadbBenchmarkThreads=8 -PadbBenchmarkSqlDiagnostics=false -PadbBenchmarkTableEngine=adb -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_raw_commit_ts_range_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-mixed-raw-commit-ts-range-stage/adb-benchmark;DB_CLOSE_DELAY=0
```

Results:

| workload | threads | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | Result file |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `range_scan` | 1 | 916.59 | 1013 | 1790 | 2548 | 502091 | `vexra-adb/build/adb-benchmark/range_count_raw_commit_ts_stage.properties` |
| `mixed` | 8 | 2413.52 | 2467 | 8388 | 11377 | 710757 | `vexra-adb/build/adb-benchmark/jdbc_mixed_raw_commit_ts_range_stage.properties` |

Conclusion:

- In the single-version benchmark, `range_scan` allocation remains around
  `502KB/op`, and `mixed` remains around `710KB/op`. This confirms that the
  dominant allocation source is not `RowValue.Metadata`; it is still the ldb
  cursor key/value/Slice/BlockEntry boundary.
- The optimization is most useful for multi-version old snapshots: range count
  can skip newer committed update/delete versions without reading their values.
- Item 5 is still not fully solved. Materially reducing wide range count and
  mixed allocation still requires segment/block-level count metadata or a
  `vexra-ldb` raw-view / reusable-entry cursor API.

## Round 44: Raw-Key Checks for Local-Write Range Count

Round 43 optimized the no-local-write raw range-count path. This round narrows
the local-write transaction path as well. `resolveVisibleCountableInCurrentLogicalRow(...)`
previously created `VersionKey.fromBytes(...)` for every version to check
whether it was committed, then read value metadata. This path is used when the
transaction has local inserts or deletes, so it is still part of item 5, the
range-count outer-entry optimization.

Changes:

1. When the current key is a fixed-length row version raw key, the method now
   uses `isRawCommittedVersion(...)` to distinguish intent from committed
   versions without constructing `VersionRowKey`.
2. For committed versions, `rawCommitTs(...)` is checked before reading the
   value. Versions newer than the snapshot are skipped without calling
   `scan.value()`.
3. Non-raw keys keep the previous `VersionKey.fromBytes(...)` fallback to avoid
   widening compatibility risk.

Verification commands:

```powershell
.\gradlew.bat --% :vexra-adb:test --tests net.xdob.vexra.adb.db.TxnManagerVisibleRowFastPathTest --rerun-tasks
```

```powershell
.\gradlew.bat --% :vexra-adb:test --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.preparedPrimaryKeyRangeCountUsesAdbDriverFastPath --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.preparedRangeCountSeesLocalInsertDeleteAndRollback --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.repeatedPreparedRangeCountReusesPlanSession --rerun-tasks
```

Result: passed.

New test:

- `shouldKeepSnapshotRangeCountWithLocalWriteWhenNewerVersionsExist` covers a
  transaction with a local write, newer committed update/delete versions, and a
  range count that must include the local new row.

Benchmarks:

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkWorkload=range_scan -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkRangeSize=32 -PadbBenchmarkThreads=1 -PadbBenchmarkSqlDiagnostics=false -PadbBenchmarkTableEngine=adb -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/range_count_local_raw_version_skip_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/range-count-local-raw-version-skip-stage/adb-benchmark;DB_CLOSE_DELAY=0
```

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkWorkload=mixed -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkRangeSize=32 -PadbBenchmarkThreads=8 -PadbBenchmarkSqlDiagnostics=false -PadbBenchmarkTableEngine=adb -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_local_raw_version_skip_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-mixed-local-raw-version-skip-stage/adb-benchmark;DB_CLOSE_DELAY=0
```

Results:

| workload | threads | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | Result file |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `range_scan` | 1 | 1700.68 | 528 | 957 | 1596 | 502356 | `vexra-adb/build/adb-benchmark/range_count_local_raw_version_skip_stage.properties` |
| `mixed` | 8 | 2276.18 | 2574 | 8956 | 12637 | 662659 | `vexra-adb/build/adb-benchmark/jdbc_mixed_local_raw_version_skip_stage.properties` |

Conclusion:

- The `range_scan` throughput sample returned to the `1700 ops/s` range, but
  allocation is still around `502KB/op`. The current single-version range
  benchmark is still dominated by the ldb cursor boundary.
- This round is more meaningful for local-write, multi-version old-snapshot
  scenarios: it avoids `VersionKey` object decoding and value reads for newer
  versions.
- The remaining high-value part of item 5 is no longer a small ADB-side cleanup:
  ADB needs segment/block-level count metadata, or `vexra-ldb` needs a
  raw/reusable cursor, to materially reduce `range_scan/mixed` allocation.

## Round 45: Direct Delegate Mode for Local Write Batch

This round continues item 4, focusing on commit / write batch allocations. The
JFR review still showed `commit / write batch` as one ADB-visible allocation
source. `LdbStore.writeBatch(...)` and `RocksStore.writeBatch(...)` previously
wrapped every `put/delete/deleteRange` call into a `WriteEn` entry in
`AdbWriteBatch.entries`, then copied those entries into the native write batch.
That intermediate representation is useful for Raft / remote replication,
because it needs to serialize writes into protocol messages. For local LDB/Rocks
commit and bulk insert, however, it only adds objects and list growth.

Changes:

1. `AdbWriteBatch` now supports a direct delegate mode that forwards
   `put/delete/deleteRange` directly to the underlying `DelegateWriteBatch`.
2. `LdbStore.writeBatch(...)` and `RocksStore.writeBatch(...)` now use direct
   mode, avoiding `WriteEn` allocation and the second `writeTo(...)` pass.
3. The default `new AdbWriteBatch(store)` mode still collects entries, so
   `RaftStore` continues to build `WriteEntry` protocol messages without
   semantic changes.
4. Direct mode wraps delegate `SQLException` in `DirectWriteBatchException`;
   the outer store unwraps it back to `SQLException`.

Validation:

```powershell
.\gradlew.bat :vexra-adb:compileJava
```

```powershell
.\gradlew.bat :vexra-adb:test --tests net.xdob.vexra.adb.db.AdbWriteBatchTest --tests net.xdob.vexra.adb.ldb.LdbStoreReliabilityTest --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.bulkInsertsRowsAndSecondaryIndexEntries --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.appendsMultipleBulkBatchesInOneTransaction --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.rejectsDuplicatePrimaryKeyThroughBulkInsertPath --rerun-tasks
```

Result: passed.

New tests:

- `AdbWriteBatchTest.shouldWriteDirectlyToDelegateWithoutCollectingEntries`
  verifies that direct mode calls the delegate without collecting `WriteEn`
  entries.
- `AdbWriteBatchTest.shouldWrapDelegateSqlExceptionInDirectMode` verifies that
  direct mode preserves the underlying `SQLException`.

Benchmarks:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark -PadbBenchmarkMode=jdbc -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkRangeSize=32 -PadbBenchmarkSqlDiagnostics=false -PadbBenchmarkWorkload=insert -PadbBenchmarkThreads=1 -PadbBenchmarkTransactionBatchSize=100 -PadbBenchmarkStatementBatchSize=100 -PadbBenchmarkTableEngine=adb -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_insert_direct_write_batch_20260622-160402.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc_insert_direct_write_batch-20260622-160402/adb-benchmark;DB_CLOSE_DELAY=0
```

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark -PadbBenchmarkMode=jdbc_bulk -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkRangeSize=32 -PadbBenchmarkSqlDiagnostics=false -PadbBenchmarkWorkload=insert -PadbBenchmarkThreads=1 -PadbBenchmarkTransactionBatchSize=1 -PadbBenchmarkStatementBatchSize=100 -PadbBenchmarkTableEngine=adb -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_bulk_insert_direct_write_batch_20260622-160402.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc_bulk_insert_direct_write_batch-20260622-160402/adb-benchmark;DB_CLOSE_DELAY=0
```

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark -PadbBenchmarkMode=jdbc -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkRangeSize=32 -PadbBenchmarkSqlDiagnostics=false -PadbBenchmarkWorkload=mixed -PadbBenchmarkThreads=8 -PadbBenchmarkTransactionBatchSize=100 -PadbBenchmarkStatementBatchSize=0 -PadbBenchmarkTableEngine=adb -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_direct_write_batch_20260622-160402.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc_mixed_direct_write_batch-20260622-160402/adb-benchmark;DB_CLOSE_DELAY=0
```

Results:

| workload | mode | threads | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | Result file |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `insert` | `jdbc` | 1 | 31578.95 | 25 | 61 | 69 | 3460 | `vexra-adb/build/adb-benchmark/jdbc_insert_direct_write_batch_20260622-160402.properties` |
| `insert` | `jdbc_bulk` | 1 | 103448.28 | 8 | 17 | 21 | 2564 | `vexra-adb/build/adb-benchmark/jdbc_bulk_insert_direct_write_batch_20260622-160402.properties` |
| `mixed` | `jdbc` | 8 | 2620.09 | 2212 | 5417 | 9479 | 590265 | `vexra-adb/build/adb-benchmark/jdbc_mixed_direct_write_batch_20260622-160402.properties` |

Conclusion:

- The `jdbc_bulk` insert sample improved from the previous `93750 ops/s` to
  `103448 ops/s`, showing that removing the local `WriteEn` layer helps bulk
  writes.
- Regular JDBC batch insert remains in the `30k ops/s` range with roughly
  `3.5KB/op` allocation.
- The mixed sample reached `2620 ops/s`, p99 `9479us`, and `590KB/op`
  allocation. This improves over the Round 44 sample, but allocation remains far
  above H2 because range count / ldb cursor key-value boundaries still dominate.
- Item 4 has now removed one local write-batch object layer. Higher-value future
  write optimizations are secondary-index key construction reuse for multi-row
  transactions, commit-stage key/value encoding reuse, and a lower-level h2db
  Insert bulk callback.

## Round 46: Bulk Secondary-Index Write Path

This round continues item 4, focusing on secondary-index bulk insert. Previously
`TxnMap2.putIndexKeys(...)` re-encoded `ValueNull.INSTANCE` for every secondary
index key and called `getVisible(indexKey)`, opening one version scan per index
entry. The method is currently used by the bulk-insert secondary-index path; the
caller has already validated primary-key conflicts, unique-index conflicts, and
in-batch conflicts before writing. A regular non-unique secondary-index key also
contains the rowId, so the local transactional write-set insert does not need to
read the old visible value again.

Changes:

1. `TxnMap2.putIndexKeys(...)` now reuses a static empty index payload and writes
   to the local transaction write set with a `null` oldValue, avoiding one
   visibility scan per secondary-index entry.
2. `TxnManager.addIndexBatch(...)` reuses the same empty index payload to reduce
   repeated encoding during commit / batch writes.
3. `AdbBenchmarkMain` now supports `--secondaryIndex true|false` to track indexed
   write costs reproducibly.
4. The semantic boundary is unchanged: bulk-insert primary-key, unique-index,
   in-batch conflict, rollback, and savepoint behavior remains covered by the
   existing path and integration tests; Raft / remote replication semantics are
   unchanged.

Validation:

```powershell
.\gradlew.bat :vexra-adb:test --tests net.xdob.vexra.adb.AdbBenchmarkMainTest.shouldRunInsertBenchmarkWithSecondaryIndex --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.bulkInsertsRowsAndSecondaryIndexEntries --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.rejectsDuplicateSecondaryUniqueKeyThroughBulkInsertPath --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.rollsBackBulkInsertedSecondaryIndexEntries --rerun-tasks
```

Result: passed.

New test:

- `AdbBenchmarkMainTest.shouldRunInsertBenchmarkWithSecondaryIndex` verifies
  that the benchmark can create a secondary index and write `secondaryIndex=true`
  to the result file.

Benchmarks:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark -PadbBenchmarkMode=jdbc -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkRangeSize=32 -PadbBenchmarkSqlDiagnostics=false -PadbBenchmarkWorkload=insert -PadbBenchmarkThreads=1 -PadbBenchmarkTransactionBatchSize=100 -PadbBenchmarkStatementBatchSize=100 -PadbBenchmarkTableEngine=adb -PadbBenchmarkSecondaryIndex=true -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_insert_secondary_index_fast_20260622-162106.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc_insert_secondary_index_fast-20260622-162106/adb-benchmark;DB_CLOSE_DELAY=0
```

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark -PadbBenchmarkMode=jdbc_bulk -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkRangeSize=32 -PadbBenchmarkSqlDiagnostics=false -PadbBenchmarkWorkload=insert -PadbBenchmarkThreads=1 -PadbBenchmarkTransactionBatchSize=1 -PadbBenchmarkStatementBatchSize=100 -PadbBenchmarkTableEngine=adb -PadbBenchmarkSecondaryIndex=true -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_bulk_insert_secondary_index_fast_20260622-162106.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc_bulk_insert_secondary_index_fast-20260622-162106/adb-benchmark;DB_CLOSE_DELAY=0
```

Results:

| workload | mode | secondary index | threads | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | Result file |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `insert` | `jdbc` | true | 1 | 18867.92 | 45 | 110 | 137 | 5502 | `vexra-adb/build/adb-benchmark/jdbc_insert_secondary_index_fast_20260622-162106.properties` |
| `insert` | `jdbc_bulk` | true | 1 | 20979.02 | 29 | 187 | 302 | 4407 | `vexra-adb/build/adb-benchmark/jdbc_bulk_insert_secondary_index_fast_20260622-162106.properties` |

Conclusion:

- This round establishes a repeatable indexed-write benchmark baseline. It has a
  different cost model from the no-secondary-index insert samples, so the
  throughput values should not be compared directly.
- In the indexed-write samples, `jdbc_bulk` allocates less than regular JDBC
  batch, confirming that the bulk entry still has an allocation advantage after
  skipping index visibility scans and reusing the empty index payload.
- The next higher-value write optimizations are secondary-index key construction
  reuse, commit-stage key/value encoding reuse, and a lower-level h2db Insert
  bulk callback. The main `range/mixed` allocation issue still needs ldb
  raw/reusable cursor support or segment/block-level count metadata.

## Round 47: ADB vs Native H2 File Tables

This round compares the current ADB table engine with native h2db file tables,
as requested. Both sides use the same `AdbBenchmarkMain` JDBC benchmark. The
only difference is the table engine:

- ADB: `jdbc:adb:ldb:*` plus `ENGINE "adb_table"`.
- H2: `jdbc:h2:*` plus a native H2 table.

Parameters:

| Parameter | Value |
| --- | --- |
| Date | 2026-06-22 |
| Rows | 5000 |
| Warmup operations | 300 |
| Measured operations | 3000 |
| Range size | 32 |
| Insert | `transactionBatchSize=100`, `statementBatchSize=100`, single thread |
| point/table/range | single thread |
| mixed | 8 threads, `transactionBatchSize=100` |
| Secondary index | disabled |

Validation: each workload was run once for ADB and once for H2. The outer
PowerShell loop reached its timeout before printing the final stamp, but every
Gradle benchmark sub-run reported `BUILD SUCCESSFUL`, and all result properties
files were written.

Results:

| workload | ADB ops/s | H2 ops/s | ADB/H2 | ADB p99 us | H2 p99 us | ADB alloc bytes/op | H2 alloc bytes/op |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `insert` | 27027.03 | 36144.58 | 0.75x | 73 | 53 | 3453 | 2636 |
| `point_lookup` | 1504.51 | 369.37 | 4.07x | 1894 | 5773 | 9928 | 36126 |
| `table_count` | 1304.35 | 328.55 | 3.97x | 2063 | 6969 | 9382 | 34950 |
| `range_scan` | 1191.90 | 346.82 | 3.44x | 2377 | 6105 | 502877 | 37423 |
| `mixed` | 2237.14 | 18181.82 | 0.12x | 11531 | 5167 | 580216 | 3556 |

Result files:

| workload | ADB result file | H2 result file |
| --- | --- | --- |
| `insert` | `vexra-adb/build/adb-benchmark/adb_insert_h2_compare_20260622-162759.properties` | `vexra-adb/build/adb-benchmark/h2_insert_h2_compare_20260622-162759.properties` |
| `point_lookup` | `vexra-adb/build/adb-benchmark/adb_point_lookup_h2_compare_20260622-162759.properties` | `vexra-adb/build/adb-benchmark/h2_point_lookup_h2_compare_20260622-162759.properties` |
| `table_count` | `vexra-adb/build/adb-benchmark/adb_table_count_h2_compare_20260622-162759.properties` | `vexra-adb/build/adb-benchmark/h2_table_count_h2_compare_20260622-162759.properties` |
| `range_scan` | `vexra-adb/build/adb-benchmark/adb_range_scan_h2_compare_20260622-162759.properties` | `vexra-adb/build/adb-benchmark/h2_range_scan_h2_compare_20260622-162759.properties` |
| `mixed` | `vexra-adb/build/adb-benchmark/adb_mixed_h2_compare_20260622-162759.properties` | `vexra-adb/build/adb-benchmark/h2_mixed_h2_compare_20260622-162759.properties` |

Conclusion:

- ADB has clear workload-specific wins for `point_lookup`, `table_count`, and
  `range_scan`, with throughput around `3.44x` to `4.07x` H2 and lower p99
  latency.
- Regular JDBC `insert` is still only `0.75x` H2 in this run. The `jdbc_bulk`
  entry can reach higher throughput, but it is not an equivalent native-H2 entry
  point and should not be used for this direct table.
- `mixed` is currently the weakest result at only `0.12x` H2. ADB mixed combines
  point lookup, range count, and write-path cost, while allocation still sits at
  roughly `580KB/op`. This matches the earlier JFR conclusion: the main mixed
  bottleneck remains the ldb cursor key/value boundary, range-count scanning,
  and combined write-path overhead.
- If the goal is to approach or beat H2 overall, the highest-value next step is
  no longer point lookup. It is mixed-workload work: add segment/block-level
  count metadata or ldb reusable cursors for range count, continue reducing
  regular JDBC insert key/value and secondary-index construction cost, and split
  the mixed workload into smaller sub-benchmarks so one aggregate score does not
  hide the source bottleneck.

## Round 48: Range Seek rowId Encoding Fix

This round continues item 5, the range-count outer-entry optimization. While
reviewing the `COUNT(*) WHERE ID BETWEEN ? AND ?` fast path, we found that
`VersionRowKey` / `RowKey` encode rowId with `flipSign(rowId)` to preserve
signed-long lexicographic order, but `TxnManager.buildRowSeekKey(...)` wrote the
raw rowId when building range-scan lower / upper bounds.

That mismatch did not change COUNT correctness because the outer loop still
filtered by rowId range. It did, however, make positive primary-key ranges seek
before the real row keys, so some scans degenerated into starting near the
beginning of the table and skipping rows until the lower bound. This matches the
previously high ldb cursor key/value allocation in range and mixed workloads.

Changes:

1. `TxnManager.buildRowSeekKey(...)` now writes `Key.flipSign(rowId)`, matching
   `VersionRowKey` / `RowKey` encoding.
2. The method now builds the seek key with a fixed-size byte array, removing the
   temporary `DynamicByteBuffer`.
3. A new unit test compares the range lower bound with real `VersionRowKey`
   bytes to prevent future regressions to raw rowId encoding.

Validation:

```powershell
.\gradlew.bat :vexra-adb:test --tests net.xdob.vexra.adb.db.TxnManagerVisibleRowFastPathTest --rerun-tasks
```

```powershell
.\gradlew.bat :vexra-adb:test --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.preparedPrimaryKeyRangeCountUsesAdbDriverFastPath --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.preparedRangeCountSeesLocalInsertDeleteAndRollback --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.repeatedPreparedRangeCountReusesPlanSession --rerun-tasks
```

Result: passed.

New test:

- `TxnManagerVisibleRowFastPathTest.shouldEncodeRangeSeekKeyWithVersionRowKeyOrder`
  verifies that the range seek key has the same rowId lexicographic order as
  `VersionRowKey`.

Benchmarks:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark -PadbBenchmarkMode=jdbc -PadbBenchmarkWorkload=range_scan -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkRangeSize=32 -PadbBenchmarkThreads=1 -PadbBenchmarkTransactionBatchSize=1 -PadbBenchmarkStatementBatchSize=0 -PadbBenchmarkSqlDiagnostics=false -PadbBenchmarkTableEngine=adb -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/adb_range_scan_row_seek_flip_repeat_20260622-164405.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/adb-range-scan-row-seek-flip-repeat-20260622-164405/adb-benchmark;DB_CLOSE_DELAY=0
```

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark -PadbBenchmarkMode=jdbc -PadbBenchmarkWorkload=mixed -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkRangeSize=32 -PadbBenchmarkThreads=8 -PadbBenchmarkTransactionBatchSize=100 -PadbBenchmarkStatementBatchSize=0 -PadbBenchmarkSqlDiagnostics=false -PadbBenchmarkTableEngine=adb -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/adb_mixed_row_seek_flip_20260622-164236.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/adb-mixed-row-seek-flip-20260622-164236/adb-benchmark;DB_CLOSE_DELAY=0
```

Results:

| workload | Before ops/s | After ops/s | Before p99 us | After p99 us | Before alloc bytes/op | After alloc bytes/op | Result file |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `range_scan` | 1191.90 | 1958.22 | 2377 | 1327 | 502877 | 95759 | `vexra-adb/build/adb-benchmark/adb_range_scan_row_seek_flip_repeat_20260622-164405.properties` |
| `mixed` | 2237.14 | 2697.84 | 11531 | 8676 | 580216 | 372208 | `vexra-adb/build/adb-benchmark/adb_mixed_row_seek_flip_20260622-164236.properties` |

Conclusion:

- `range_scan` allocation dropped from roughly `502KB/op` to `96KB/op`.
  Throughput improved from `1191.90 ops/s` to `1958.22 ops/s`, and p99 dropped
  from `2377us` to `1327us`.
- `mixed` allocation dropped from roughly `580KB/op` to `372KB/op`. Throughput
  improved from `2237.14 ops/s` to `2697.84 ops/s`, and p99 dropped from
  `11531us` to `8676us`.
- This confirms that the range-count outer seek bound was one of the most
  valuable fixes in this area. Mixed is still far from H2, so the next steps are
  further reducing ldb cursor boundary allocation or adding segment/block-level
  count metadata.

## Round 49: Remove Temporary value byte[] Copy in Point-Lookup Column Decode

This round continues item 3, visible-row / point-lookup parsing-path
optimization. After `RowValue.decodeValue(...)`, `RowCodec.decodeColumn(...)`
and `decodeColumns(...)` located the selected column inside the Row payload,
allocated a temporary `valueBytes` array, copied the encoded column bytes into
it, and then called `safeDecode(ByteBuffer.wrap(valueBytes))`.

For the benchmark query `SELECT NAME FROM ADB_BENCH WHERE ID = ?`, each point
lookup only decodes one column. The extra byte-array copy does not change SQL
semantics, but it does add deterministic allocation on cache-miss / new-key
point lookups.

Changes:

1. Added `RowCodec.decodeCurrentValue(ByteBuffer, int)`, which uses
   `ByteBuffer.duplicate()` to create a bounded view and decode the selected
   value directly from the original payload.
2. `decodeColumn(...)` and `decodeColumns(...)` now use that helper for selected
   columns and advance the original buffer to the end of the column value.
3. Row format, returned `Value` semantics, and the `ValueNull` behavior for
   missing columns are unchanged.

Validation:

```powershell
.\gradlew.bat :vexra-adb:test --tests net.xdob.vexra.adb.db.RowCodecTest --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.preparedPrimaryKeyLookupUsesAdbDriverFastPath --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.preparedPrimaryKeyLookupReportsDetailedPhases --rerun-tasks
```

Result: passed.

Benchmarks:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark -PadbBenchmarkMode=jdbc -PadbBenchmarkWorkload=point_lookup -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkRangeSize=32 -PadbBenchmarkThreads=1 -PadbBenchmarkTransactionBatchSize=1 -PadbBenchmarkStatementBatchSize=0 -PadbBenchmarkSqlDiagnostics=false -PadbBenchmarkTableEngine=adb -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/adb_point_lookup_decode_view_repeat_20260622-165545.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/adb-point-lookup-decode-view-repeat-20260622-165545/adb-benchmark;DB_CLOSE_DELAY=0
```

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark -PadbBenchmarkMode=jdbc -PadbBenchmarkWorkload=mixed -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkRangeSize=32 -PadbBenchmarkThreads=8 -PadbBenchmarkTransactionBatchSize=100 -PadbBenchmarkStatementBatchSize=0 -PadbBenchmarkSqlDiagnostics=false -PadbBenchmarkTableEngine=adb -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/adb_mixed_decode_view_20260622-165403.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/adb-mixed-decode-view-20260622-165403/adb-benchmark;DB_CLOSE_DELAY=0
```

Results:

| workload | Baseline ops/s | This round ops/s | Baseline p99 us | This round p99 us | Baseline alloc bytes/op | This round alloc bytes/op | Result file |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `point_lookup` | 1504.51 | 1020.76 | 1894 | 2177 | 9928 | 9922 | `vexra-adb/build/adb-benchmark/adb_point_lookup_decode_view_repeat_20260622-165545.properties` |
| `mixed` | 2697.84 | 2700.27 | 8676 | 8670 | 372208 | 371846 | `vexra-adb/build/adb-benchmark/adb_mixed_decode_view_20260622-165403.properties` |

Conclusion:

- This round is a small allocation cleanup, not a major throughput optimization:
  mixed allocation dropped from `372208 B/op` to `371846 B/op`, while throughput
  stayed effectively flat.
- The point-lookup standalone throughput did not improve in this run. That
  indicates the standalone point lookup is currently more affected by store
  seek, cache-hit validation, and benchmark / disk variance than by this column
  value copy.
- The change is still useful because it removes deterministic allocation from
  cache-miss / new-key point lookups. The next higher-value item 3 work remains
  safely reducing committed-cache hit validation cost, or pushing selected-column
  decoding further down so it can read directly from the store value without
  copying the RowValue payload first.

## Round 50: Manual RowValue Header Decode

This round continues item 3, `TxnMap2.getVisible / visible row` parsing-path
optimization. `RowValue.decodeValue(...)` and `decodeMetadata(...)` previously
created a `ByteBuffer.wrap(data)` object on every decode, then read the fixed
`txnId / commitTs / deleted / payloadLength` layout. This path is used by point
lookup, range-count multi-version visibility checks, and committed store scans.

Changes:

1. `RowValue.decodeValue(...)` now reads long / int / byte fields from fixed
   offsets, avoiding a `HeapByteBuffer` wrapper per decode.
2. `RowValue.decodeMetadata(...)` uses the same manual header decode for range
   count and visibility checks.
3. Empty payloads reuse a shared `EMPTY_PAYLOAD`, avoiding a new `byte[0]` for
   delete / empty-payload versions.
4. The RowValue disk format is unchanged. Non-empty payloads are still copied
   into an independent byte array, so callers do not hold the underlying store
   value.

Validation:

```powershell
.\gradlew.bat :vexra-adb:test --tests net.xdob.vexra.adb.db.RowValueTest --tests net.xdob.vexra.adb.db.RowCodecTest --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.preparedPrimaryKeyLookupUsesAdbDriverFastPath --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.preparedPrimaryKeyLookupReportsDetailedPhases --rerun-tasks
```

Result: passed.

New tests:

- `RowValueTest.shouldDecodeValueAndMetadataFromEncodedBytes` covers full
  payload value / metadata decode.
- `RowValueTest.shouldReuseEmptyPayloadForDeletedRows` covers empty-payload
  reuse and metadata `hasPayload=false`.

Benchmarks:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark -PadbBenchmarkMode=jdbc -PadbBenchmarkWorkload=point_lookup -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkRangeSize=32 -PadbBenchmarkThreads=1 -PadbBenchmarkTransactionBatchSize=1 -PadbBenchmarkStatementBatchSize=0 -PadbBenchmarkSqlDiagnostics=false -PadbBenchmarkTableEngine=adb -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/adb_point_lookup_rowvalue_manual_decode_20260622-170419.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/adb-point_lookup-rowvalue-manual-decode-20260622-170419/adb-benchmark;DB_CLOSE_DELAY=0
```

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark -PadbBenchmarkMode=jdbc -PadbBenchmarkWorkload=mixed -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkRangeSize=32 -PadbBenchmarkThreads=8 -PadbBenchmarkTransactionBatchSize=100 -PadbBenchmarkStatementBatchSize=0 -PadbBenchmarkSqlDiagnostics=false -PadbBenchmarkTableEngine=adb -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/adb_mixed_rowvalue_manual_decode_20260622-170419.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/adb-mixed-rowvalue-manual-decode-20260622-170419/adb-benchmark;DB_CLOSE_DELAY=0
```

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark -PadbBenchmarkMode=jdbc -PadbBenchmarkWorkload=mixed -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkRangeSize=32 -PadbBenchmarkThreads=8 -PadbBenchmarkTransactionBatchSize=100 -PadbBenchmarkStatementBatchSize=0 -PadbBenchmarkSqlDiagnostics=false -PadbBenchmarkTableEngine=adb -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/adb_mixed_rowvalue_manual_decode_repeat_20260622-170526.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/adb-mixed-rowvalue-manual-decode-repeat-20260622-170526/adb-benchmark;DB_CLOSE_DELAY=0
```

Results:

| workload | Baseline ops/s | This round ops/s | Repeat ops/s | Baseline p99 us | This round p99 us | Repeat p99 us | Baseline alloc bytes/op | This round alloc bytes/op | Result file |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `point_lookup` | 1020.76 | 1411.10 | - | 2177 | 1958 | - | 9922 | 10258 | `vexra-adb/build/adb-benchmark/adb_point_lookup_rowvalue_manual_decode_20260622-170419.properties` |
| `mixed` | 2700.27 | 3045.69 | 2868.07 | 8670 | 7560 | 8250 | 371846 | 371102 / 371162 | `vexra-adb/build/adb-benchmark/adb_mixed_rowvalue_manual_decode_20260622-170419.properties` |

Conclusion:

- Both mixed samples are above the Round 49 baseline: `3045.69 ops/s` and
  `2868.07 ops/s`, with p99 at `7560us` and `8250us`. This is a positive signal
  for the visible-row / range-metadata path.
- Allocation only dropped modestly, from `371846 B/op` to roughly
  `371102-371162 B/op`, which is expected because the dominant sources remain
  the ldb cursor key/value boundary and store-value copying.
- The standalone point-lookup sample recovered to `1411 ops/s` from the
  previous low sample, but allocation did not improve. It is still not a good
  one-run trend indicator.
- The next higher-value item 3 work remains safely reducing physical-version
  validation on committed-cache hits, or adding a selected-column visible-row API
  that can decode directly from the store value and skip RowValue payload copy.

## Round 51: Current ADB vs Native H2 File-Table Retest

This round reruns the requested `adb_table` versus native h2db file-table
comparison. Both sides use the same `AdbBenchmarkMain` JDBC benchmark. The only
differences are the table engine and JDBC URL:

- ADB: `jdbc:adb:ldb:*` plus `ENGINE "adb_table"`;
- H2: `jdbc:h2:*` plus the native H2 table.

Parameters:

| Parameter | Value |
| --- | --- |
| `rows` | 5000 |
| `warmupOperations` | 300 |
| `operations` | 3000 |
| `rangeSize` | 32 |
| `sqlDiagnostics` | false |
| `insert` / `point_lookup` / `table_count` / `range_scan` | 1 thread, `transactionBatchSize=1` |
| `mixed` | 8 threads, `transactionBatchSize=100` |
| Secondary index | disabled |

Results:

| workload | ADB ops/s | H2 ops/s | ADB/H2 | ADB p99 us | H2 p99 us | ADB alloc bytes/op | H2 alloc bytes/op |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `insert` | 530.88 | 503.19 | 1.06x | 4563 | 5080 | 36317 | 37862 |
| `point_lookup` | 1168.22 | 485.36 | 2.41x | 2083 | 5260 | 10138 | 36776 |
| `table_count` | 1805.05 | 400.21 | 4.51x | 1967 | 5372 | 9622 | 35612 |
| `range_scan` | 1370.49 | 342.08 | 4.01x | 2125 | 9852 | 92863 | 38186 |
| `mixed` | 2606.43 | 30000.00 | 0.09x | 8766 | 2140 | 371506 | 3579 |

Result files:

| workload | ADB result file | H2 result file |
| --- | --- | --- |
| `insert` | `vexra-adb/build/adb-benchmark/adb_insert_h2_compare_current_20260622-171452.properties` | `vexra-adb/build/adb-benchmark/h2_insert_h2_compare_current_20260622-171452.properties` |
| `point_lookup` | `vexra-adb/build/adb-benchmark/adb_point_lookup_h2_compare_current_20260622-171452.properties` | `vexra-adb/build/adb-benchmark/h2_point_lookup_h2_compare_current_20260622-171452.properties` |
| `table_count` | `vexra-adb/build/adb-benchmark/adb_table_count_h2_compare_current_20260622-171452.properties` | `vexra-adb/build/adb-benchmark/h2_table_count_h2_compare_current_20260622-171452.properties` |
| `range_scan` | `vexra-adb/build/adb-benchmark/adb_range_scan_h2_compare_current_20260622-171452.properties` | `vexra-adb/build/adb-benchmark/h2_range_scan_h2_compare_current_20260622-171452.properties` |
| `mixed` | `vexra-adb/build/adb-benchmark/adb_mixed_h2_compare_current_20260622-171452.properties` | `vexra-adb/build/adb-benchmark/h2_mixed_h2_compare_current_20260622-171452.properties` |

Compared with the Round 47 H2 comparison baseline:

| workload | ADB throughput change | H2 throughput change | Note |
| --- | ---: | ---: | --- |
| `insert` | -98.0% | -98.6% | Both sides dropped by roughly the same amount, so this single-row autocommit insert run appears dominated by local file flush / environment variance and is not a good optimization-trend signal |
| `point_lookup` | -22.4% | +31.4% | ADB is still faster than H2, but the ratio fell from `4.07x` to `2.41x` |
| `table_count` | +38.4% | +21.8% | ADB improved from `3.97x` to `4.51x` H2 |
| `range_scan` | +15.0% | -1.4% | After the range-seek fix, ADB improved from `3.44x` to `4.01x` H2 |
| `mixed` | +16.5% | +65.0% | ADB improved, but H2 was much faster in this run; mixed remains the largest gap |

Conclusion:

- The clearest current wins are `range_scan` and `table_count`: ADB is about
  `4.01x` and `4.51x` H2 respectively.
- `point_lookup` is still faster than H2 at about `2.41x`, but this standalone
  workload is sensitive to cache, store seek, and filesystem variance, so small
  optimization wins should not be inferred from one run.
- `insert` landed at roughly `500 ops/s` for both ADB and H2, far away from the
  Round 47 sample on both sides. It should be retested with a longer window or
  batch-insert profile before being used as a trend signal.
- `mixed` remains the highest-value optimization target: ADB improved by about
  `16.5%` versus Round 47, but is still only `0.09x` H2 in this run. The likely
  gap remains in the range-count outer path, visibility parsing, combined write
  costs, and the JDBC / table-engine boundary.

## Round 52: Decode Single-Column Point Lookup Directly from the Visible RowValue Payload Range

This round continues item 3, the `TxnMap2.getVisible / visible row` parsing-path
optimization. Previously, single-column
`SELECT NAME FROM ADB_BENCH WHERE ID = ?` first called
`map.getVisible(rowKey)` to obtain a `RowValue`. `RowValue.decodeValue(...)`
copied the full payload into an independent byte array, and then
`RowCodec.decodeColumn(...)` decoded the selected column.

Changes:

1. `RowValue` now exposes package-local header helpers for `commitTs`, the
   delete flag, payload length, and payload offset.
2. `RowCodec.decodeColumn(...)` now has a byte-range overload, allowing a
   selected column to be decoded directly from the payload subrange inside the
   encoded RowValue bytes.
3. `TxnManager.getVisibleColumn(...)` / `TxnMap2.getVisibleColumn(...)` add a
   single-column visible-value entry point. Local writes, region point-read
   routing, and committed-row-cache validation keep the existing semantics. When
   the committed store scan is needed, the selected column is decoded directly
   from `scan.value()` without copying the RowValue payload first.
4. `AdbPreparedPointLookupPlan` now uses the new path for single-column
   projections. Multi-column queries and `SELECT *` keep the previous
   `RowValue` path.

Validation:

```powershell
.\gradlew.bat :vexra-adb:test --tests net.xdob.vexra.adb.db.RowCodecTest --tests net.xdob.vexra.adb.db.TxnManagerVisibleRowFastPathTest --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.preparedPrimaryKeyLookupUsesAdbDriverFastPath --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.preparedPrimaryKeyLookupReportsDetailedPhases --rerun-tasks
```

Result: passed.

New tests:

- `RowCodecTest.decodeColumnReadsPayloadSubRangeWithoutCopy` covers decoding a
  column directly from the payload subrange inside encoded RowValue bytes.
- `TxnManagerVisibleRowFastPathTest.shouldDecodeVisibleColumnFromCommittedStoreValue`
  covers snapshot visibility with a newer committed version present, while
  returning only the selected column.

Benchmarks:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark -PadbBenchmarkMode=jdbc -PadbBenchmarkWorkload=point_lookup -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkRangeSize=32 -PadbBenchmarkThreads=1 -PadbBenchmarkTransactionBatchSize=1 -PadbBenchmarkStatementBatchSize=0 -PadbBenchmarkSqlDiagnostics=false -PadbBenchmarkTableEngine=adb -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/adb_point_lookup_visible_column_direct_20260622-173340.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/adb_point_lookup_visible_column_direct-20260622-173340/adb-benchmark;DB_CLOSE_DELAY=0
```

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark -PadbBenchmarkMode=jdbc -PadbBenchmarkWorkload=mixed -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkRangeSize=32 -PadbBenchmarkThreads=8 -PadbBenchmarkTransactionBatchSize=100 -PadbBenchmarkStatementBatchSize=0 -PadbBenchmarkSqlDiagnostics=false -PadbBenchmarkTableEngine=adb -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/adb_mixed_visible_column_direct_20260622-173340.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/adb_mixed_visible_column_direct-20260622-173340/adb-benchmark;DB_CLOSE_DELAY=0
```

Results:

| workload | Round 51 ADB ops/s | This round ops/s | Change | Round 51 p99 us | This round p99 us | Round 51 alloc bytes/op | This round alloc bytes/op | Result file |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `point_lookup` | 1168.22 | 1228.00 | +5.1% | 2083 | 2003 | 10138 | 10334 | `vexra-adb/build/adb-benchmark/adb_point_lookup_visible_column_direct_20260622-173340.properties` |
| `mixed` | 2606.43 | 2762.43 | +6.0% | 8766 | 8243 | 371506 | 391563 | `vexra-adb/build/adb-benchmark/adb_mixed_visible_column_direct_20260622-173340.properties` |

Conclusion:

- Direct selected-column decoding from the RowValue payload subrange shows a
  positive throughput signal for both `point_lookup` and `mixed`, at about
  `+5.1%` and `+6.0%`.
- Allocation did not drop in the same way, which indicates the current sample
  is still dominated by the store value / cursor boundary, ResultSet proxy, and
  outer JDBC / table-engine objects. This round only removes one payload copy
  when a committed store scan is the source of the visible row.
- This is worth keeping as a narrow item 3 improvement, but the next higher
  value work should still use JFR evidence to decide whether to build dedicated
  ResultSet implementations, or continue reducing committed-cache validation and
  the mixed workload's range / write combined costs.

## Round 53: Mixed 8-Thread JFR Allocation Hotspot Check

This round completes performance objective item 1: instead of continuing to
guess where object allocation comes from, it records a full JFR run for the
current `mixed` 8-thread JDBC path. `scripts/AdbJfrHotspots.java` was enhanced
to aggregate allocation stack traces even on local environments without the
`jfr` CLI. The commit focus rules were also tightened so
`getVisibleCommitted*` no longer gets misclassified as a commit hotspot just
because it contains the word `committed`.

JFR command:

```powershell
powershell.exe -ExecutionPolicy Bypass -File .\scripts\adb-benchmark-jfr.ps1 -Workload mixed -Rows 5000 -WarmupOperations 300 -Operations 3000 -Threads 8 -RangeSize 32 -Mode jdbc -TableEngine adb -SqlDiagnostics false -OutputDir vexra-adb/build/adb-benchmark/jfr-visible-column-direct
```

Hotspot parsing command:

```powershell
powershell.exe -ExecutionPolicy Bypass -File .\scripts\adb-jfr-hotspots.ps1 -JfrFile vexra-adb\build\adb-benchmark\jfr-visible-column-direct\adb-mixed-20260622-174131.jfr -OutputDir vexra-adb\build\adb-benchmark\jfr-visible-column-direct\hotspots-stack-v2
```

JFR benchmark result:

| workload | threads | operations | throughput ops/s | p99 us | alloc bytes/op | Result file |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| `mixed` | 8 | 3000 | 507.44 | 703122 | 647713 | `vexra-adb/build/adb-benchmark/jfr-visible-column-direct/adb-mixed-20260622-174131.properties` |

The throughput is heavily affected by JFR recording overhead. Use it only for
allocation ranking, not as a normal throughput baseline.

Corrected focus allocations:

| focus | bytes | events | Interpretation |
| --- | ---: | ---: | --- |
| `AdbPreparedStatementProxy` | 172096 | 4210 | Mostly covers ldb cursor iteration under the range-count / prepared fast path |
| `TxnMap2.getVisible` | 101528 | 2690 | Still a steady allocation source in the visible-row path |
| `TxnManager.commit` | 38768 | 913 | Real commit/write allocations exist, but are not the largest allocation source in this run |
| `WriteBatch` | 7888 | 27 | The write-batch path allocates a small amount and remains relevant for later write optimization |
| `AdbSimpleResultSet` | 3976 | 4 | Mostly first-use loading around `singleLong`, not a steady hotspot |
| `java.lang.reflect.Proxy` | 2136 | 4 | Mostly first-use proxy class generation, not a steady hotspot |
| `RowCodec` | 1072 | 11 | No longer a major allocation source |
| `RowValue.decodeValue` | 64 | 2 | The direct column decode work has effectively removed it from the allocation hot list |

Stack trace conclusions:

- The largest overall `[B` allocations include two roughly 1MB `HeapByteBuffer`
  allocations from H2 `MVStore/FileStore` write buffers, plus class loading and
  jar-reading noise. These are not ADB steady-state fast-path allocations and
  should not drive the next ADB code optimization.
- The recurring ADB-related hotspots are concentrated around the ldb cursor /
  block iteration boundary: `Slice`, `InternalKey`, `BlockEntry`, and
  `BasicSliceOutput`, with stacks going through
  `DbSnapshotCursor.positionToVisible`, `LdbVersionEntryCursor.seekToRangeStart`,
  `TxnManager.getVisibleCommittedRow/getVisibleCommittedColumn`, and
  `TxnManager.countVisibleRows...`.
- JFR does not prove `AdbSimpleResultSet` / `java.lang.reflect.Proxy` to be
  steady allocation dominants. Therefore item 2 should not build dedicated
  ResultSet implementations yet. Unless a later JFR run shows a larger
  ResultSet/Proxy share, it should stay below the current optimization targets.
- The next higher-value target is the intersection of item 5 and item 3:
  optimize the range-count outer entry and the visible-row ldb cursor/version
  positioning cost. Start with prepared range-count plan caching, count-only
  cursor seek/entry-construction reduction, and whether committed-cache
  validation can avoid some physical-version scans.

## Round 54: Range Count Local-Write Range Filtering

This round continues objective item 5. JFR showed the range-count allocations in
`mixed` are mostly around ldb cursor / block iteration and visible-row version
positioning. Looking at the benchmark revealed another issue: `mixed` uses
`transactionBatchSize=100`, so the transaction write set becomes non-empty after
the first append insert. Previously, even when those local writes had rowIds
outside the `COUNT(*) WHERE ID BETWEEN ? AND ?` range,
`TxnManager.countVisibleRows(...)` fell back to the object-heavy local-write
merge path.

Changes:

1. `TxnManager.countVisibleRows(...)` now checks whether local row writes
   intersect the requested rowId range when the write set is non-empty.
2. If all local writes are outside the requested range, the method still uses
   the `countVisibleRowsWithoutLocalWrites(...)` raw fast path and avoids the
   unnecessary `VersionKey/DataKey/HashSet` merge path.
3. If a local insert/delete is inside the requested range, the previous slow
   path is preserved, keeping read-your-writes and rollback semantics.

Validation:

```powershell
.\gradlew.bat :vexra-adb:test --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.preparedPrimaryKeyRangeCountUsesAdbDriverFastPath --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.preparedRangeCountSeesLocalInsertDeleteAndRollback --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.preparedRangeCountUsesRawPathWhenLocalWritesAreOutsideRange --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.repeatedPreparedRangeCountReusesPlanSession --rerun-tasks
```

Result: passed.

New test:

- `AdbTableProviderIntegrationTest.preparedRangeCountUsesRawPathWhenLocalWritesAreOutsideRange`
  covers a transaction with an out-of-range local insert. The prepared range
  count returns the correct result and records `ADB_RANGE_COUNT_VISIBLE_COUNT_RAW`.

Diagnostic benchmark:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark -PadbBenchmarkMode=jdbc -PadbBenchmarkWorkload=mixed -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=1000 -PadbBenchmarkRangeSize=32 -PadbBenchmarkThreads=8 -PadbBenchmarkTransactionBatchSize=100 -PadbBenchmarkStatementBatchSize=0 -PadbBenchmarkSqlDiagnostics=true -PadbBenchmarkTableEngine=adb -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/adb_mixed_range_count_local_write_filter_diag_20260622-180525.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/adb_mixed_range_count_local_write_filter_diag-20260622-180525/adb-benchmark;DB_CLOSE_DELAY=0
```

Non-diagnostic benchmark:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark -PadbBenchmarkMode=jdbc -PadbBenchmarkWorkload=mixed -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkRangeSize=32 -PadbBenchmarkThreads=8 -PadbBenchmarkTransactionBatchSize=100 -PadbBenchmarkStatementBatchSize=0 -PadbBenchmarkSqlDiagnostics=false -PadbBenchmarkTableEngine=adb -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/adb_mixed_range_count_local_write_filter_repeat_20260622-180557.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/adb_mixed_range_count_local_write_filter_repeat-20260622-180557/adb-benchmark;DB_CLOSE_DELAY=0
```

Results:

| workload | ops/s | p99 us | alloc bytes/op | Result file |
| --- | ---: | ---: | ---: | --- |
| `mixed` diagnostic sample, 1000 ops | 2785.52 | 7821 | not recorded | `vexra-adb/build/adb-benchmark/adb_mixed_range_count_local_write_filter_diag_20260622-180525.properties` |
| `mixed` non-diagnostic sample, 3000 ops | 2727.27 | 8654 | 388679 | `vexra-adb/build/adb-benchmark/adb_mixed_range_count_local_write_filter_repeat_20260622-180557.properties` |

Conclusion:

- The diagnostic sample confirms that all 200 range-count operations hit
  `ADB_RANGE_COUNT_VISIBLE_COUNT_RAW`; the raw inner phase averaged about
  `101us`, and the outer `ADB_RANGE_COUNT_VISIBLE_COUNT` phase averaged about
  `118us`.
- Allocation fell slightly from the Round 52 mixed sample's `391563 B/op` to
  `388679 B/op`.
- The end-to-end throughput short run does not prove an improvement:
  `2727 ops/s` is below Round 52's `2762 ops/s`, and also below the later ADB/H2
  retest sample at `2994 ops/s`. Treat this as range-count path narrowing and
  allocation cleanup, not as the main throughput optimization being done.
- Follow-up work still needs to reduce range-count ldb cursor seek / block
  entry construction cost, or push the count-only path closer to the
  store/block layer.

## Round 55: Direct Single-Row Prepared Insert Bulk Entry

This round continues objective item 4 by optimizing the `BULK_ADD_ROW / ADD_ROW`
write entry. Parameterized single-row
`INSERT INTO ... VALUES (?, ?)` already hit the ADB bulk insert path, but it
still constructed `Collections.singletonList(row)` and then entered the
single-row branch inside `bulkInsertAppendRows(...)`. In `mixed`, 10% of
operations are single-row append inserts. The object boundary is small, but it
is deterministic write-entry overhead.

Changes:

1. `AdbTable` now has `bulkInsertAppendRow(SessionLocal, Row)`, which executes
   single-row uniqueness checks, encoded-row writes, and optional secondary
   index registration directly.
2. `bulkInsertAppendRows(...)` delegates to the new single-row entry when
   `rows.size() == 1`. The multi-row path keeps the previous batch duplicate
   detection and batch append high-water logic.
3. `AdbPreparedInsertPlan` now constructs a single `Row` and calls the new entry
   directly when `rowCount == 1`. Multi-values INSERT still uses the previous
   multi-row bulk path.

Validation:

```powershell
.\gradlew.bat :vexra-adb:test --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.preparedSingleValuesInsertUsesAdbDriverBulkPath --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.preparedMultiValuesInsertUsesAdbDriverBulkPath --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.preparedRangeCountUsesRawPathWhenLocalWritesAreOutsideRange --rerun-tasks
```

Result: passed.

Benchmarks:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark -PadbBenchmarkMode=jdbc -PadbBenchmarkWorkload=insert -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkRangeSize=32 -PadbBenchmarkThreads=1 -PadbBenchmarkTransactionBatchSize=1 -PadbBenchmarkStatementBatchSize=0 -PadbBenchmarkSqlDiagnostics=false -PadbBenchmarkTableEngine=adb -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/adb_insert_single_row_bulk_entry_20260622-181304.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/adb_insert_single_row_bulk_entry-20260622-181304/adb-benchmark;DB_CLOSE_DELAY=0
```

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark -PadbBenchmarkMode=jdbc -PadbBenchmarkWorkload=mixed -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkRangeSize=32 -PadbBenchmarkThreads=8 -PadbBenchmarkTransactionBatchSize=100 -PadbBenchmarkStatementBatchSize=0 -PadbBenchmarkSqlDiagnostics=false -PadbBenchmarkTableEngine=adb -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/adb_mixed_single_row_bulk_entry_20260622-181304.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/adb_mixed_single_row_bulk_entry-20260622-181304/adb-benchmark;DB_CLOSE_DELAY=0
```

Results:

| workload | ops/s | p99 us | alloc bytes/op | Result file |
| --- | ---: | ---: | ---: | --- |
| `insert` | 697.51 | 3070 | 37408 | `vexra-adb/build/adb-benchmark/adb_insert_single_row_bulk_entry_20260622-181304.properties` |
| `mixed` | 3024.19 | 8140 | 387340 | `vexra-adb/build/adb-benchmark/adb_mixed_single_row_bulk_entry_20260622-181304.properties` |

Conclusion:

- `mixed` recovered from the Round 54 non-diagnostic sample at
  `2727.27 ops/s` to `3024.19 ops/s`, and allocation continued to fall from
  `388679 B/op` to `387340 B/op`.
- Compared with Round 52's `2762.43 ops/s`, this round is about `+9.5%`. It is
  also slightly above the later ADB/H2 retest sample at `2994.01 ops/s`, which
  is a positive signal for the combination of write-entry object cleanup and
  range-count local-write filtering.
- The standalone `insert` sample reached `697.51 ops/s`, slightly above the
  later ADB/H2 retest sample at `680.74 ops/s`. This workload is still strongly
  affected by file-store flush behavior, so it needs a longer window before it
  can be treated as a stable trend.

## Round 56: Deferred PreparedStatement Setter Replay

This round continues the shared outer-entry optimization for objective items 3,
4, and 5. The diagnostic sample showed:

- `ADB_TABLE_POINT_LOOKUP_FAST` averaged about `2270us`;
- `ADB_TABLE_BULK_ADD_ROW` averaged about `2420us`;
- `ADB_TABLE_RANGE_COUNT_FAST` averaged about `2260us`;
- but the inner `ADB_RANGE_COUNT_VISIBLE_COUNT_RAW` phase averaged only about
  `140us`.

This means `mixed` is not only limited by the raw count internals. The
`PreparedStatement` fast-path boundary is also expensive. Previously,
`AdbPreparedStatementProxy` recorded every `setLong/setString` parameter and
also invoked the H2 delegate setter reflectively. When the fast path succeeds,
the delegate never uses those parameters.

Changes:

1. `AdbPreparedStatementProxy` now defers parameter setter replay. On fast-path
   execution it only records the parameter value and the latest setter call.
2. When a fast path misses and execution falls back to the H2 delegate, the
   proxy replays the current setter state before invoking the delegate.
3. `clearParameters()` clears both local parameter state and delegate
   parameters, preventing fallback from seeing stale values.
4. Setter records are stored by parameter position and overwritten by later
   setters, so high-frequency execution of one PreparedStatement does not keep
   accumulating historical setters.

Validation:

```powershell
.\gradlew.bat :vexra-adb:test --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.preparedFallbackReplaysLatestDeferredParameters --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.preparedNonPrimaryRangeCountFallsBackToH2Path --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.preparedPrimaryKeyLookupUsesAdbDriverFastPath --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.preparedPrimaryKeyRangeCountUsesAdbDriverFastPath --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.preparedSingleValuesInsertUsesAdbDriverBulkPath --rerun-tasks
```

Result: passed.

New test:

- `AdbTableProviderIntegrationTest.preparedFallbackReplaysLatestDeferredParameters`
  covers H2 fallback for non-primary-key range count. Deferred setters replay
  the latest parameters, and repeated execution of the same PreparedStatement
  does not reuse stale values.

Diagnostic benchmark:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark -PadbBenchmarkMode=jdbc -PadbBenchmarkWorkload=mixed -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=1000 -PadbBenchmarkRangeSize=32 -PadbBenchmarkThreads=8 -PadbBenchmarkTransactionBatchSize=100 -PadbBenchmarkStatementBatchSize=0 -PadbBenchmarkSqlDiagnostics=true -PadbBenchmarkTableEngine=adb -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/adb_mixed_after_single_bulk_diag_20260622-181804.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/adb_mixed_after_single_bulk_diag-20260622-181804/adb-benchmark;DB_CLOSE_DELAY=0
```

Non-diagnostic benchmarks:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark -PadbBenchmarkMode=jdbc -PadbBenchmarkWorkload=point_lookup -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkRangeSize=32 -PadbBenchmarkThreads=1 -PadbBenchmarkTransactionBatchSize=1 -PadbBenchmarkStatementBatchSize=0 -PadbBenchmarkSqlDiagnostics=false -PadbBenchmarkTableEngine=adb -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/adb_point_lookup_deferred_setters_20260622-182110.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/adb_point_lookup_deferred_setters-20260622-182110/adb-benchmark;DB_CLOSE_DELAY=0
```

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark -PadbBenchmarkMode=jdbc -PadbBenchmarkWorkload=mixed -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkRangeSize=32 -PadbBenchmarkThreads=8 -PadbBenchmarkTransactionBatchSize=100 -PadbBenchmarkStatementBatchSize=0 -PadbBenchmarkSqlDiagnostics=false -PadbBenchmarkTableEngine=adb -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/adb_mixed_deferred_setters_20260622-182110.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/adb_mixed_deferred_setters-20260622-182110/adb-benchmark;DB_CLOSE_DELAY=0
```

Results:

| workload | ops/s | p99 us | alloc bytes/op | Result file |
| --- | ---: | ---: | ---: | --- |
| `point_lookup` | 1321.00 | 1836 | 10175 | `vexra-adb/build/adb-benchmark/adb_point_lookup_deferred_setters_20260622-182110.properties` |
| `mixed` | 3205.13 | 7437 | 388947 | `vexra-adb/build/adb-benchmark/adb_mixed_deferred_setters_20260622-182110.properties` |

Conclusion:

- `mixed` improved from Round 55's `3024.19 ops/s` to `3205.13 ops/s`, about
  `+6.0%`, with p99 falling from `8140us` to `7437us`.
- `point_lookup` is about `+7.6%` over Round 52's `1228.00 ops/s`, but still
  below the later ADB/H2 retest sample at `1642.94 ops/s`. This standalone
  workload still needs multiple samples before reading it as a stable trend.
- Allocation did not fall, as expected. This round removes delegate setter
  reflection calls on successful fast paths rather than eliminating large
  allocations.
- The next target should stay around the fast-path outer boundary: either
  further reduce PreparedStatement proxy invocation cost, or address
  `AdbSimpleResultSet` / count ResultSet call overhead. From the JFR allocation
  perspective, ResultSet is still not a major allocation source.

## Round 57: Trusted Cache Invalidation After Region Snapshot Install

This round continues the safety prerequisites for objective item 3,
`TxnMap2.getVisible / visible row` optimization. Earlier benchmarks showed that
`-Dvexra.adb.rowCache.trustCommitted=true` can clearly improve point-read and
mixed throughput, because a committed row cache hit can skip the physical
committed-version existence check. This mode still cannot simply become the
default: if a restore or region snapshot install replaces store contents in the
same process and the existing `TxnManager` cache is not invalidated, later point
lookups may return values from before the snapshot install.

Changes:

1. `AdbRegionSnapshotInstaller` now has an optional constructor parameter for
   `TxnManager`.
2. After a successful region snapshot restore, the installer calls
   `invalidateStoreDerivedCaches()` when a `TxnManager` was supplied, clearing
   committed row cache, row-count cache, and rowId hints.
3. The existing `AdbRegionSnapshotInstaller(DbStore, String)` constructor is
   preserved for callers that only need snapshot installation and do not own a
   transaction manager.
4. A new
   `AdbRegionTopologyManagerTest.shouldInvalidateTrustedTxnCacheAfterSnapshotInstall`
   case first caches an old value in the target store under trusted cache mode,
   then installs a source checkpoint snapshot and verifies that the same
   `TxnManager` reads the snapshot value.

Validation:

```powershell
.\gradlew.bat :vexra-adb:test --tests net.xdob.vexra.adb.db.AdbRegionTopologyManagerTest.shouldInvalidateTrustedTxnCacheAfterSnapshotInstall --tests net.xdob.vexra.adb.db.AdbRegionTopologyManagerTest.shouldInstallSnapshotIntoTargetStoreAndReadCommittedData --tests net.xdob.vexra.adb.db.AdbRuntimeOperationsBridgeTest.shouldRunFullBackupAndRestoreDrill --rerun-tasks
```

Result: passed.

Conclusion:

- This round does not change the default `trustCommittedRowCache=false`, so
  normal benchmark throughput does not directly change because of this commit.
- It closes another important store-replacement boundary outside runtime
  restore, moving trusted committed cache from a pure benchmark switch toward a
  locally optimizable mode with controlled invalidation.
- Safely making this benefit the default still requires covering direct
  `DbStore.restore(...)` callers, production region snapshot installer
  injection, and external store replacement notifications; otherwise skipping
  physical version validation by default still risks stale cache reads.

## Round 58: Direct Value Cache for Prepared Single-Column Point Lookup

This round continues objective item 3, `TxnMap2.getVisible / visible row`
optimization. The latest ADB/H2 comparison showed ADB still behind H2 on
single-thread `point_lookup`. For prepared single-column lookups such as
`SELECT NAME FROM TEST WHERE ID = ?`, even a decoded-value cache hit still had
to enter `TxnMap2.getVisibleColumn(...)` first to obtain the visible commitTs.

Changes:

1. `TxnManager.VisibleColumnValue` now exposes `latestCommitted()`.
   `getVisibleCommittedColumn(...)` returns `latestCommitted=false` when it had
   to skip a newer committed version for the current transaction snapshot. This
   prevents old snapshot reads from being cached as the latest table value by
   upper layers.
2. `AdbPreparedPointLookupPlan` now has a direct value cache for prepared
   single-column point lookups. Cache entries record rowId, commitTs,
   `AdbTable.getMaxDataModificationId()`, and the `latestCommitted` flag. Only
   entries from the latest committed version, with an unchanged table
   modification id, can skip `TxnMap2.getVisibleColumn(...)` and build the
   ResultSet directly.
3. If one point-lookup plan observes repeated table modification-id changes, it
   adaptively disables the direct value cache to avoid low-hit cache checks in
   mixed workloads with continuous writes. The default threshold is
   `-Dadb.pointLookup.valueCacheModificationChangeLimit=8`.
4. When the table modification id changes, the plan clears its decoded/value
   caches so update/delete paths cannot reuse old values.

Validation:

```powershell
.\gradlew.bat :vexra-adb:test --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.preparedPointLookupDecodeCacheSeesCommittedUpdateAndDelete --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest.preparedPrimaryKeyLookupUsesAdbDriverFastPath --tests net.xdob.vexra.adb.db.TxnManagerVisibleRowFastPathTest.shouldDecodeVisibleColumnFromCommittedStoreValue --tests net.xdob.vexra.adb.db.TxnManagerVisibleRowFastPathTest.shouldMarkVisibleColumnAsNotLatestWhenNewerVersionExists --rerun-tasks
```

Result: passed.

Benchmarks:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark -PadbBenchmarkMode=jdbc -PadbBenchmarkWorkload=point_lookup -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkRangeSize=32 -PadbBenchmarkThreads=1 -PadbBenchmarkTransactionBatchSize=1 -PadbBenchmarkStatementBatchSize=0 -PadbBenchmarkSqlDiagnostics=false -PadbBenchmarkTableEngine=adb -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/adb_point_lookup_value_cache_adaptive_20260623-084423.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/adb_point_lookup_value_cache_adaptive-20260623-084423/adb-benchmark;DB_CLOSE_DELAY=0
```

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark -PadbBenchmarkMode=jdbc -PadbBenchmarkWorkload=mixed -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkRangeSize=32 -PadbBenchmarkThreads=8 -PadbBenchmarkTransactionBatchSize=100 -PadbBenchmarkStatementBatchSize=0 -PadbBenchmarkSqlDiagnostics=false -PadbBenchmarkTableEngine=adb -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/adb_mixed_value_cache_adaptive_20260623-084423.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/adb_mixed_value_cache_adaptive-20260623-084423/adb-benchmark;DB_CLOSE_DELAY=0
```

Direct-value-cache disabled comparison:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark -PadbBenchmarkJvmArgs=-Dadb.pointLookup.valueCacheModificationChangeLimit=0 -PadbBenchmarkMode=jdbc -PadbBenchmarkWorkload=mixed -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkRangeSize=32 -PadbBenchmarkThreads=8 -PadbBenchmarkTransactionBatchSize=100 -PadbBenchmarkStatementBatchSize=0 -PadbBenchmarkSqlDiagnostics=false -PadbBenchmarkTableEngine=adb -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/adb_mixed_value_cache_disabled_20260623-084504.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/adb_mixed_value_cache_disabled-20260623-084504/adb-benchmark;DB_CLOSE_DELAY=0
```

Results:

| workload | ops/s | p99 us | Result file |
| --- | ---: | ---: | --- |
| `point_lookup` | 2744.74 | 1401 | `vexra-adb/build/adb-benchmark/adb_point_lookup_value_cache_adaptive_20260623-084423.properties` |
| `mixed`, adaptive direct value cache | 2901.35 | 8376 | `vexra-adb/build/adb-benchmark/adb_mixed_value_cache_adaptive_20260623-084423.properties` |
| `mixed`, direct value cache disabled | 2857.14 | 8270 | `vexra-adb/build/adb-benchmark/adb_mixed_value_cache_disabled_20260623-084504.properties` |

Conclusion:

- Single-thread `point_lookup` improved from Round 56's `1321.00 ops/s` to
  `2744.74 ops/s`, about `+107.8%`. Compared with the latest pre-change ADB/H2
  comparison ADB sample at `1100.51 ops/s`, the gain is about `+149.4%`.
- Compared with the same H2 point-lookup sample at `1528.27 ops/s`, current ADB
  point lookup is about `1.80x`. This confirms that the single-column prepared
  point-lookup visibility boundary was a high-value optimization point.
- This round's `mixed` sample is below Round 56's `3205.13 ops/s`, but the
  adaptive and explicitly disabled direct-value-cache runs are close
  (`2901.35` vs `2857.14 ops/s`). This does not prove the new cache is the cause
  of the mixed drop. Mixed still needs continued work on write entry, range
  count, and transaction-boundary costs.

## Round 59: Mixed Detailed Diagnostics and Rejected Experiments

This round continues the five-item performance objective, but does not keep
negative-throughput production code. It first reran an 8-thread mixed detailed
diagnostic sample:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark -PadbBenchmarkMode=jdbc -PadbBenchmarkWorkload=mixed -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=1000 -PadbBenchmarkRangeSize=32 -PadbBenchmarkThreads=8 -PadbBenchmarkTransactionBatchSize=100 -PadbBenchmarkStatementBatchSize=0 -PadbBenchmarkSqlDiagnostics=true -PadbBenchmarkDetailedDiagnostics=true -PadbBenchmarkTableEngine=adb -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/adb_mixed_detail_after_value_cache_20260623-091500.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/adb_mixed_detail_after_value_cache-20260623-091500/adb-benchmark;DB_CLOSE_DELAY=0
```

Diagnostic sample result:

| workload | threads | ops | ops/s | p99 us | alloc bytes/op | Result file |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| `mixed` detailed | 8 | 1000 | 3174.60 | 7108 | 138334 | `vexra-adb/build/adb-benchmark/adb_mixed_detail_after_value_cache_20260623-091500.properties` |

Main phases:

| phase | count | avg us | total us | Notes |
| --- | ---: | ---: | ---: | --- |
| `ADB_TABLE_POINT_LOOKUP_FAST ADB_BENCH` | 700 | 2167 | 1517000 | Highest-frequency mixed read entry |
| `ADB_TABLE_RANGE_COUNT_FAST ADB_BENCH` | 200 | 2180 | 436000 | Range-count outer entry is still high |
| `ADB_TABLE_BULK_ADD_ROW ADB_BENCH` | 100 | 2600 | 260000 | Write entry is still high |
| `ADB_POINT_LOOKUP_VISIBLE_ROW` | 700 | 109 | 76875 | Visible internals are no longer the millisecond-scale cause |
| `ADB_VISIBLE_COMMITTED_STORE_SCAN` | 594 | 91 | 54502 | Store seek/scan can still be split further |
| `ADB_RANGE_COUNT_VISIBLE_COUNT_RAW` | 200 | 88 | 17709 | Raw range counting is not the largest remaining cost |

Rejected experiments:

| Experiment | Evidence | Decision |
| --- | --- | --- |
| Skip savepoint for single-row append-safe bulk insert | `insert` reached `729.04 ops/s`, but `mixed` repeated at `2669.04` and `2739.73 ops/s`, below the retained baseline | Helps pure insert only; rejected for mixed |
| Row-level change-version direct value cache | `point_lookup` stayed high at `2732.24 ops/s`, but `mixed` was `2762.43 ops/s`; detailed diagnostics showed only `28/700` `ADB_POINT_LOOKUP_VALUE_CACHE_HIT` events | Benchmark point keys mostly do not repeat, so hits do not offset commit-side version maintenance; rejected |
| Skip point-lookup phase timing when SQL diagnostics are disabled | `point_lookup` repeated at `1192.84` and `2207.51 ops/s`, below Round 58's `2744.74 ops/s` | Branch/call shape did not produce stable positive throughput; rejected |

Conclusion:

- No new production optimization was retained in this round.
- The only retained commit is the diagnostic-test assertion fix from the prior
  turn: `preparedPointLookupRecordsVisibleRowDiagnosticBreakdown` now accepts
  the outer direct value cache hit path as a valid substitute for internal
  committed-cache hit/validate phases.
- Objective item 2 should still stay deferred: prior JFR evidence shows
  `AdbSimpleResultSet` / `java.lang.reflect.Proxy` are not steady allocation
  dominants.
- The next higher-value direction is another JFR pass around
  `AdbPreparedStatementProxy` / JDBC outer invocation boundaries, or an ldb-side
  range cursor/raw-view and segment/block-level count capability that can
  materially reduce `range_scan/mixed` allocation.

## Round 60: JFR Recheck and Committed Cache Content Epoch Invalidation

This round first completed objective item 1 by rerunning a full 8-thread
`mixed` JFR profile instead of guessing around `ResultSet` / `Proxy`. Command:

```powershell
powershell.exe -ExecutionPolicy Bypass -File .\scripts\adb-benchmark-jfr.ps1 -Workload mixed -Rows 5000 -WarmupOperations 300 -Operations 3000 -Threads 8 -RangeSize 32 -Mode jdbc -TableEngine adb -SqlDiagnostics false -OutputDir vexra-adb/build/adb-benchmark/jfr-round60-current
```

JFR files:

- `vexra-adb/build/adb-benchmark/jfr-round60-current/adb-mixed-20260623-093401.jfr`
- `vexra-adb/build/adb-benchmark/jfr-round60-current/hotspots/adb-focus.txt`

Key allocation evidence:

| Focus | Allocation sample | Conclusion |
| --- | ---: | --- |
| `AdbPreparedStatementProxy` | `251616 bytes / 4920 events` | Mostly ldb cursor/block iteration and key/value byte objects |
| `TxnMap2.getVisible` | `134712 bytes / 3157 events` | Mostly committed-version seek/scan ldb objects |
| `TxnManager.commit` | `39304 bytes / 1095 events` | Write-batch path still has object cost, but is not the top item in this round |
| `java.lang.reflect.Proxy` | `33032 bytes / 3 events` | Mostly first proxy-class generation, not a steady-state hotspot |
| `AdbSimpleResultSet` | `152 bytes / 5 events` | Not an allocation hotspot |

So objective item 2 remains deferred. This round instead continues objective
item 3, reducing the committed-cache validation cost in
`TxnMap2.getVisible / visible row` paths.

Changes:

1. `DbStore` now exposes default `contentEpoch()` and
   `supportsContentEpoch()` methods for whole-store content replacement.
2. `LdbStore` and `RocksStore` increment `contentEpoch` after successful
   restore.
3. `TxnManager` now defaults to trusting committed row cache only when the store
   supports content epochs, skipping the per-cache-hit `store.get(versionKey)`
   physical version validation.
4. Before using read paths, row-count cache, or append rowId hints,
   `TxnManager` checks the store content epoch. If a direct
   `DbStore.restore(...)` bypassed the runtime bridge or region snapshot
   installer, it clears committed row cache, row-count cache, and rowId hints.
5. Compatibility switches remain available:
   `-Dvexra.adb.rowCache.validateCommitted=true` forces the old conservative
   validation behavior, and explicit
   `-Dvexra.adb.rowCache.trustCommitted=false` disables default trusted cache.

New test:

- `TxnManagerVisibleRowFastPathTest.shouldInvalidateDefaultTrustedCacheAfterDirectLdbRestore`
  covers direct `LdbStore.restore(...)` under the default trusted-cache mode and
  verifies that the same `TxnManager` does not return pre-restore cached data.

Validation:

```powershell
.\gradlew.bat :vexra-adb:test --tests net.xdob.vexra.adb.db.TxnManagerVisibleRowFastPathTest.shouldInvalidateDefaultTrustedCacheAfterDirectLdbRestore --tests net.xdob.vexra.adb.db.TxnManagerVisibleRowFastPathTest.shouldKeepSnapshotVisibleWhenNewerCommittedVersionExists --tests net.xdob.vexra.adb.db.AdbRuntimeOperationsBridgeTest.shouldRunFullBackupAndRestoreDrill --tests net.xdob.vexra.adb.db.AdbRegionTopologyManagerTest.shouldInvalidateTrustedTxnCacheAfterSnapshotInstall
```

Result: passed.

Benchmark comparison used the same shape: `rows=5000`, `warmup=300`,
`operations=3000`, `rangeSize=32`, and `sqlDiagnostics=false`.

| workload | cache mode | ops/s | p99 us | alloc bytes/op | Result file |
| --- | --- | ---: | ---: | ---: | --- |
| `point_lookup` | default trusted + content epoch | 2189.78 | 2225 | 10410 | `vexra-adb/build/adb-benchmark/cache-epoch-20260623-095032/point_default.properties` |
| `point_lookup` | forced physical validation | 2309.47 | 2166 | 10733 | `vexra-adb/build/adb-benchmark/cache-epoch-20260623-095032/point_validate.properties` |
| `mixed`, 8 threads | default trusted + content epoch | 2617.80 | 8595 | 389603 | `vexra-adb/build/adb-benchmark/cache-epoch-20260623-095032/mixed_default.properties` |
| `mixed`, 8 threads | forced physical validation | 2529.51 | 9119 | 390760 | `vexra-adb/build/adb-benchmark/cache-epoch-20260623-095032/mixed_validate.properties` |

Conclusion:

- The JFR evidence does not justify making dedicated `ResultSet` the next
  priority. `AdbSimpleResultSet` and `java.lang.reflect.Proxy` are not
  steady-state allocation dominants.
- On 8-thread `mixed`, default trusted cache improved throughput by about
  `+3.5%` over forced physical validation, reduced p99 from `9119us` to
  `8595us`, and reduced allocation from `390760 B/op` to `389603 B/op`.
- The single-thread `point_lookup` sample was lower with default trusted cache
  than with forced validation in this run, so the benefit should be read as a
  mixed-workload improvement rather than a stable point-lookup win.
- The next priority should stay on the ldb cursor/block allocation indicated by
  JFR: either request raw-view / lower-allocation cursor support from
  `vexra-ldb`, or further reduce `VersionScanSource.key()` / `value()` array
  and Slice allocation in ADB range-count / visible-row paths.

## Round 61: Raw Range-Count Intent Advancement Fix

This round continues the overlap between objective 3 (`TxnMap2.getVisible` /
visible-row parsing) and objective 5 (range-count outer entry). After reviewing
the current ADB code and the `vexra-ldb:0.10.0` API, the boundary is clear:

1. ADB `VersionScanSource` still exposes only `byte[] key()` / `byte[] value()`.
2. `vexra-ldb` `SnapshotCursor` also exposes only `byte[] key()` /
   `byte[] value()`, and `DbSnapshotCursor.positionToVisible(...)` copies the
   current key/value internally.
3. ADB therefore cannot fully remove the cursor/block/key-value allocation seen
   in JFR from this side alone. Larger gains still require a `vexra-ldb`
   raw-view / reusable-entry cursor, or ADB segment/block-level count metadata.

Within the part that ADB can fix directly, this round found and fixed one raw
range-count intent-advancement issue. When
`resolveVisibleCountableInCurrentRawLogicalRow(...)` saw an intent version for
the current logical row, it called `scan.advance()` but did not refresh the local
`rawKey`. Because `VersionRowKey` sorts the intent marker before the committed
marker, the helper could keep evaluating the stale intent key until the cursor
became invalid, skipping the following committed version and later rows.

Changes:

1. Refresh the local raw key immediately after advancing through an intent
   version.
2. Add
   `TxnManagerVisibleRowFastPathTest.shouldContinueRawRangeCountAfterIntentVersion`,
   which constructs a row with both an intent and a committed version plus a
   following committed row, then verifies raw range count returns the correct
   count.

Validation command:

```powershell
.\gradlew.bat :vexra-adb:test --tests net.xdob.vexra.adb.db.TxnManagerVisibleRowFastPathTest.shouldContinueRawRangeCountAfterIntentVersion
```

Full validation:

```powershell
.\gradlew.bat :vexra-adb:test
```

Result: passed.

Benchmark:

| workload | threads | ops/s | p99 us | alloc bytes/op | Result file |
| --- | ---: | ---: | ---: | ---: | --- |
| `range_scan` | 1 | 2238.81 | 1112 | 93675 | `vexra-adb/build/adb-benchmark/range_count_intent_fix_20260623.properties` |
| `mixed` | 8 | 2247.19 | 10567 | 388470 | `vexra-adb/build/adb-benchmark/mixed_intent_fix_20260623.properties` |

Conclusion: this change mainly fixes correctness and abnormal scan amplification
when intents are present. The common no-intent benchmark path does not show a
large throughput gain, but it also does not show an obvious functional
regression. Further allocation reduction still requires a `vexra-ldb`
raw/reusable cursor or ADB segment/block-level count metadata.

## Round 62: Prepared Setter Recording Reuse Plan

This round continues objective 4 (`BULK_ADD_ROW / ADD_ROW`) and also covers the
outer `PreparedStatement` object boundary used by objectives 3 and 5. The
current write path review shows:

1. Plain `INSERT INTO ... VALUES (?, ?)` already reaches
   `AdbPreparedInsertPlan`, then `AdbTable.bulkInsertAppendRow(...)` /
   `bulkInsertAppendRows(...)`.
2. Multi-row bulk insert, append high-water, batched uniqueness checks, and
   secondary-index bulk writes already exist.
3. The earlier "skip savepoint" experiment helped pure insert but regressed
   `mixed`, so it should not be kept.
4. `AdbPreparedStatementProxy` still creates a new `SetterCall` and clones the
   setter argument array for every `setXxx(...)` call. When the ADB insert /
   point-lookup / range-count fast path succeeds, those objects are never
   replayed to the H2 delegate and are avoidable per-operation allocations.

Changes:

1. Reuse one mutable setter record per parameter slot instead of creating a new
   `SetterCall` on every setter call.
2. Store common setters such as `setLong`, `setInt`, `setString`, `setBoolean`,
   `setObject`, and `setNull` as parameter index plus value, and replay them
   with direct delegate calls on fallback.
3. Keep reflective replay with cloned arguments for unsupported setters.
4. Preserve `clearParameters()`, fallback execution, `unwrap`, and
   `isWrapperFor` behavior.

New test:

- `AdbTableProviderIntegrationTest.preparedInsertReplaysLatestSetterValuesAfterClearParameters`
  covers setting old parameters, calling `clearParameters()`, setting new
  parameters, and then executing the fast path. This prevents reused setter
  records from leaking old values into the next execution.

Validation:

```powershell
.\gradlew.bat :vexra-adb:test
```

Result: passed.

Benchmark:

| workload | threads | ops/s | p99 us | alloc bytes/op | Result file |
| --- | ---: | ---: | ---: | ---: | --- |
| `insert` | 1 | 319.01 | 5818 | 34714 | `vexra-adb/build/adb-benchmark/insert_setter_reuse_20260623.properties` |
| `point_lookup` | 1 | 2181.82 | 1384 | 8578 | `vexra-adb/build/adb-benchmark/point_lookup_setter_reuse_20260623.properties` |
| `mixed` | 8 | 2156.72 | 12725 | 387656 | `vexra-adb/build/adb-benchmark/mixed_setter_reuse_20260623.properties` |

Conclusion:

- This does not change MVCC, write atomicity, uniqueness checks, or commit
  behavior. It mainly reduces setter-side allocation when prepared fast paths
  hit.
- `point_lookup` allocation dropped from the round-60 sample `10410 B/op` to
  `8578 B/op`, showing that the setter-side allocation was reduced while
  throughput stayed roughly flat.
- `mixed` allocation dropped slightly from the round-61 sample `388470 B/op` to
  `387656 B/op`, but the short throughput run did not show positive evidence.
  This round should be treated as allocation narrowing, not a main `mixed`
  throughput win.
- The `insert` throughput sample was much lower than surrounding runs and is
  likely dominated by file-store flush / environment variance; use only its
  allocation number as a reference.

## Round 63: Prepared Insert Parameter Access De-objectification Plan

This round continues objective 4 (`BULK_ADD_ROW / ADD_ROW`). Reviewing
`AdbPreparedInsertPlan` showed that prepared/literal insert still creates an
anonymous `ParameterAccessor` object on each execution and then reads
parameters through that interface:

1. The `Object[] parameters` path creates one anonymous accessor per
   `executeUpdate()`.
2. The literal `List<Object>` path creates one anonymous accessor as well.
3. The object only serves parameter reads inside the current fast-path
   execution and is discarded afterwards. Row conversion, uniqueness checks,
   savepoint handling, and commit semantics do not depend on this abstraction.

Changes:

1. Add direct `row(...)` / `rows(...)` construction paths for `Object[]` and
   `List<Object>`.
2. Remove the per-execution anonymous `ParameterAccessor` allocation and use
   simple array/list indexing instead.
3. Keep `Column.convert(...)`, `DefaultRow`, `convertInsertRow(...)`, and the
   bulk-write boundary unchanged.

Validation:

```powershell
.\gradlew.bat :vexra-adb:test
```

Result: passed.

Benchmark:

| workload | threads | ops/s | p99 us | alloc bytes/op | Result file |
| --- | ---: | ---: | ---: | ---: | --- |
| `insert` | 1 | 520.56 | 4730 | 34734 | `vexra-adb/build/adb-benchmark/insert_insert_param_direct_20260623.properties` |
| `point_lookup` | 1 | 1074.11 | 5990 | 8578 | `vexra-adb/build/adb-benchmark/point_lookup_insert_param_direct_20260623.properties` |
| `mixed` | 8 | 2180.23 | 11189 | 389966 | `vexra-adb/build/adb-benchmark/mixed_insert_param_direct_20260623.properties` |

Conclusion:

- The change preserves SQL semantics and removes the anonymous
  `ParameterAccessor` allocated on each prepared/literal insert fast-path
  execution.
- This round did not prove an allocation reduction: `point_lookup` stayed at
  `8578 B/op`, and `mixed` moved from `387656 B/op` to `389966 B/op`.
- Keep this as object-boundary cleanup and maintenance groundwork, but do not
  count it as a main `mixed` throughput win. The next higher-value work still
  points to ldb raw/reusable cursors, segment/block-level count, or coarser
  write batching.

## Round 64: Local-write-aware Raw Range Count Plan

This round continues objective 3 (`TxnMap2.getVisible / visible row parsing`)
and objective 5 (outer `range count` entry optimization). The latest ADB/H2
comparison shows that ADB is already much faster than H2 for `point_lookup`,
`range_scan`, and `table_count`, but 8-thread `mixed` still reaches only about
0.10x of H2, with ADB allocating about `388 KB` per operation.

Reviewing `TxnManager.countVisibleRows(...)` shows that transactions without
local writes already use the raw-key range-count path. However, once the
current transaction has any row write in the target range, the method falls
back to the object-heavy scan path:

1. Build a `VersionKey` for every store row.
2. Convert it to a `DataKey` through `VersionKey.toDataKey()`.
3. Rebuild the logical row prefix with `DataKey.toBytes()`.
4. Track store-covered local writes in a `Set<DataKey>`.

That path is highly relevant to the `mixed` combination of in-transaction writes
plus range counts. Planned changes:

1. Scan the transaction write-set once and extract local row writes for the
   target table and rowId range into a small `rowId -> RowValue` map.
2. Keep the store scan on existing raw-key helpers: parse rowId directly from
   VersionRowKey bytes, skip a logical row, check committed versions, and decode
   only RowValue metadata.
3. When a store rowId has a local write, skip the store logical row and count
   according to the local write override.
4. After the scan, add only local rowIds not covered by the store scan, avoiding
   per-row `DataKey` sets and key rebuilding.
5. Preserve MVCC, deletes, versions newer than the snapshot, intent skipping,
   and local-write override semantics.

Compatibility and rollback:

- No on-disk key/value format change.
- No change to transaction commit, locks, secondary indexes, or row-count
  metadata.
- If the raw-key preconditions do not hold, the old object path can remain as a
  fallback; this round should first reuse the existing raw-key offsets and
  `resolveVisibleCountableInCurrentRawLogicalRow(...)`.

Implementation result:

1. Added `localRowWritesInRange(...)` to first collect current-transaction local
   writes for the target table and rowId range into a `rowId -> RowValue` map.
2. Added `countVisibleRowsWithLocalWritesRaw(...)`. Range counts with local
   writes now first try a raw-key scan; rows overridden by the local write-set
   are counted from the local value, and store-covered local rowIds are tracked
   with a `Set<Long>`.
3. Kept `countVisibleRowsWithLocalWritesObject(...)` as a compatibility
   fallback when a non-raw row version key is encountered.
4. Added
   `TxnManagerVisibleRowFastPathTest.shouldCountRangeWithLocalWriteOverridesOnRawPath`
   to cover local delete, local update, local insert, and store committed rows
   in the same range count.

Validation:

```powershell
.\gradlew.bat :vexra-adb:test --tests net.xdob.vexra.adb.db.TxnManagerVisibleRowFastPathTest
.\gradlew.bat :vexra-adb:test
```

Result: passed.

Benchmark:

| workload | threads | ops/s | p99 us | alloc bytes/op | Result file |
| --- | ---: | ---: | ---: | ---: | --- |
| `range_scan` | 1 | 1109.06 | 2109 | 93763 | `vexra-adb/build/adb-benchmark/range_count_raw_local_20260623-105227.properties` |
| `mixed` | 8 | 2622.38 | 8363 | 388740 | `vexra-adb/build/adb-benchmark/mixed_raw_local_20260623-105227.properties` |

Conclusion:

- This round removes per-store-row `VersionKey/DataKey` construction from the
  "range count with local writes" path, but the default `mixed` workload inserts
  high IDs outside the range-count query range, so it does not consistently hit
  the new branch.
- Default `range_scan` / `mixed` allocation remains around `94 KB/op` /
  `389 KB/op`. The next highest-value work still points to ldb raw/reusable
  cursors, segment/block-level count, or a dedicated benchmark workload for
  "local-write-covered range count" to quantify this path directly.

## Round 65: Local-write-covered Range Count Benchmark Workload Plan

This round continues the validation loop for objectives 3 and 5. Round 64
changed "range count with local writes" from an object-heavy scan to a raw-key
scan, but the default `mixed` workload inserts rowIds around
`rows + 2_000_000 + index` while range-count queries still target `1..rows`.
Therefore default `mixed` rarely hits the new branch and cannot directly
quantify the improvement.

Planned workload: `range_count_local_write`.

1. Each operation first inserts an uncommitted row inside the queried range.
2. It then executes `SELECT COUNT(*) FROM table WHERE ID BETWEEN ? AND ?`
   covering that row.
3. With `transactionBatchSize > 1`, the count sees the local write-set override
   in the same transaction and should consistently hit
   `ADB_RANGE_COUNT_VISIBLE_COUNT_RAW_LOCAL`.
4. The workload is diagnostic only and does not change production SQL, MVCC,
   commit, locking, or on-disk formats.
5. The same workload can run against a regular h2db table as a JDBC baseline
   for local write plus range count.

Expected value:

- Provide a repeatable benchmark for the round-64 raw local range-count path.
- Separate this local optimization from the default range-scan bottleneck that
  still requires ldb raw/reusable cursors or segment-level count metadata.

Implementation result:

1. Added the `range_count_local_write` workload to `AdbBenchmarkMain`.
2. In JDBC mode, each operation first executes a prepared insert for an
   uncommitted row and then executes a prepared range count covering that rowId.
3. Store mode provides a same-name local baseline by writing one new key and
   scanning that key range.
4. Updated the Chinese and English user-guide workload tables.

New test:

- `AdbBenchmarkMainTest.shouldRunLocalWriteRangeCountBenchmarkAgainstLdbUrl`
  covers the command-line entry, properties output, and
  `ADB_RANGE_COUNT_VISIBLE_COUNT_RAW_LOCAL` diagnostic hit.

Validation:

```powershell
.\gradlew.bat :vexra-adb:test --tests net.xdob.vexra.adb.AdbBenchmarkMainTest.shouldRunLocalWriteRangeCountBenchmarkAgainstLdbUrl
.\gradlew.bat :vexra-adb:test
```

Result: passed.

Benchmark:

| engine | workload | threads | ops/s | p99 us | alloc bytes/op | Result file |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| ADB | `range_count_local_write` | 1 | 618.81 | 4894 | 347213 | `vexra-adb/build/adb-benchmark/adb_range_count_local_write_20260623-110409.properties` |
| H2 | `range_count_local_write` | 1 | 24000.00 | 693 | 5430 | `vexra-adb/build/adb-benchmark/h2_range_count_local_write_20260623-110409.properties` |

Diagnostic excerpt:

- `ADB_RANGE_COUNT_VISIBLE_COUNT_RAW_LOCAL`: 3000 calls, `279 us` average.
- `ADB_RANGE_COUNT_VISIBLE_COUNT`: 3000 calls, `286 us` average.
- `ADB_TABLE_RANGE_COUNT_FAST ADB_BENCH`: 3000 calls, `908 us` average.
- `ADB_TABLE_BULK_ADD_ROW ADB_BENCH`: 3000 calls, `669 us` average.

Conclusion:

- The new workload consistently hits the round-64 raw local range-count path and
  can be used to regress the local-write-covered scenario.
- ADB is still much slower than H2 in this diagnostic workload and allocates
  about `347 KB/op`; the bottleneck is not inside raw local count itself, but in
  the write entry, the range-count outer path, and the LDB scan/cursor
  allocation chain.
- If the next round stays inside the ADB repository, prioritize single-row
  `BULK_ADD_ROW` and the range-count outer path. A major `range_scan/mixed`
  allocation reduction still requires ldb raw/reusable cursors or
  segment-level count metadata design.
