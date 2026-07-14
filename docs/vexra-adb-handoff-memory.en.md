# vexra-adb Handoff Memory

## Scope

This document hands over test data, test rules, benchmark baselines, and historical constraints for the standalone `vexra-adb` project. It records the ADB-side validation memory accumulated in the original `vexra` repository before 2026-07-14, so the new independent repository can reproduce, compare, and submit changes without losing context.

The original repository is already in the split phase: the `vexra-adb` source directory is removed from the `vexra` working tree, while `vexra-adb-raft` remains in the original repository for Raft extensions. The independent project should continue publishing the original coordinate `net.xdob.vexra:vexra-adb`; Java packages and JDBC behavior should remain compatible.

## Base Versions and Boundaries

| Item | Handoff value / rule |
| --- | --- |
| h2db version | `net.xdob.h2db:h2db:2.3.0` |
| ldb version | `net.xdob.vexra:vexra-ldb:0.12.0-SNAPSHOT`; prefer the local Maven snapshot |
| ADB coordinate | `net.xdob.vexra:vexra-adb`; keep it after extraction |
| JDBC prefixes | Keep `jdbc:adb:mem:`, `jdbc:adb:ldb:`, and `jdbc:adb:tcp://...` compatible |
| Default performance focus | File-backed `ldb`; do not use `mem` mode as the performance baseline |
| h2db relationship | Reuse h2db SQL parser / JDBC / Server / tools directly via plugin URL prefix and table provider registration |
| vexra-ldb boundary | Treat as an upstream dependency. Do not silently modify `vexra-ldb`; document needs or get explicit authorization first |
| Raft boundary | Core `vexra-adb` must not depend on the Vexra Raft runtime; `vexra-adb-raft` owns Raft support |

## Test Entrypoints to Carry Over

The independent project should keep these Gradle tasks or equivalents:

```powershell
.\gradlew.bat test
.\gradlew.bat adbBenchmark
.\gradlew.bat adbRuntimeDist
.\gradlew.bat adbReleaseProfile
```

High-value targeted tests from the old module should be moved and kept runnable:

| Test area | Representative tests |
| --- | --- |
| H2 plugin and URL prefix | `AdbJdbcUrlPrefixProviderTest`, `AdbTableProviderIntegrationTest`, `AdbH2PluginTest`, `AdbTransactionEventProviderTest` |
| JDBC / SQL Server | `AdbSqlServerMainTest`, `AdbRuntimeDistributionSmokeTest` |
| Benchmark tool | `AdbBenchmarkMainTest` |
| LDB adapters | `LdbVersionReadSessionTest`, `LdbVersionEntryCursorTest`, `LdbStoreReliabilityTest`, `LdbStoreMultiGetTest` |
| MVCC / visibility / cache | `TxnManagerVisibleRowFastPathTest`, `BoundedSeekRegressionTest`, `RowValueTest`, `RowCodecTest` |
| Transactions and write batch | `AdbWriteBatchTest`, `AdbLockResolverTest`, `AdbCommittedVersionGcCleanerTest` |
| Production gates | `AdbReleaseProfileRunnerTest`, `AdbReleaseReadinessGateTest`, `AdbProductionGuardTest`, `AdbTrialProductionAdmissionGateTest` |

## Fixed Benchmark Shape

Short ADB vs H2DB comparison shape:

| Parameter | Value |
| --- | --- |
| rows | `150000` |
| warmupOperations | `300` |
| operations | `3000` |
| rangeSize | `4096` |
| insert transactionBatchSize | `100` |
| insert statementBatchSize | `100` |
| mixed threads | `8` |
| mixed transactionBatchSize | `100` |
| mixed statementBatchSize | `100` |
| SQL diagnostics | `false` by default for comparison runs |

Fixed workloads:

| Workload name | ADB parameters |
| --- | --- |
| `insert_batch100` | `workload=insert`, `transactionBatchSize=100`, `statementBatchSize=100` |
| `point_lookup` | `workload=point_lookup` |
| `range_scan_4096` | `workload=range_scan`, `rangeSize=4096` |
| `table_count` | `workload=table_count` |
| `mixed_threads8_batch100_range4096` | `workload=mixed`, `threads=8`, `transactionBatchSize=100`, `statementBatchSize=100`, `rangeSize=4096` |

Comparison command template:

```powershell
.\gradlew.bat adbBenchmark `
  "-PadbBenchmarkOutput=build/adb-benchmark/<run-id>/adb_<case>.properties" `
  "-PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra-adb/build/adb-benchmark/<run-id>/db/adb-<case>/benchmark;DB_CLOSE_DELAY=0" `
  "-PadbBenchmarkWorkload=<workload>" `
  "-PadbBenchmarkRows=150000" `
  "-PadbBenchmarkWarmupOperations=300" `
  "-PadbBenchmarkOperations=3000" `
  "-PadbBenchmarkRangeSize=4096" `
  "-PadbBenchmarkTransactionBatchSize=<tx-batch>" `
  "-PadbBenchmarkStatementBatchSize=<stmt-batch>" `
  "-PadbBenchmarkThreads=<threads>" `
  "-PadbBenchmarkTableEngine=adb" `
  "-PadbBenchmarkSqlDiagnostics=false"
```

For H2DB, only change the URL and table engine:

```powershell
"-PadbBenchmarkUrl=jdbc:h2:D:/work/java2/vexra-adb/build/adb-benchmark/<run-id>/db/h2-<case>/benchmark;DB_CLOSE_DELAY=0"
"-PadbBenchmarkTableEngine=h2"
```

## Latest ADB vs H2DB Snapshot Result

Dependency resolution confirmed `net.xdob.vexra:vexra-ldb:0.12.0-SNAPSHOT`.

Result directory:

```text
vexra-adb/build/adb-benchmark/ldb-snapshot-h2-compare-20260702-001
```

| workload | ADB ops/s | H2 ops/s | ADB/H2 | ADB p99 us | H2 p99 us | ADB alloc/op | H2 alloc/op |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `insert_batch100` | 38461.54 | 13274.34 | 2.90x | 41 | 161 | 2734 | 3153 |
| `point_lookup` | 35294.12 | 187.42 | 188.32x | 339 | 8589 | 2748 | 45763 |
| `range_scan_4096` | 8875.74 | 175.33 | 50.62x | 273 | 9190 | 25360 | 234222 |
| `table_count` | 115384.62 | 199.12 | 579.46x | 44 | 7223 | 989 | 42950 |
| `mixed_threads8_batch100_range4096` | 11811.02 | 8771.93 | 1.35x | 8903 | 3864 | 21666 | 35977 |

Memory-side metrics:

| workload | ADB heap peak MB | H2 heap peak MB | ADB measure peak delta MB | H2 measure peak delta MB |
| --- | ---: | ---: | ---: | ---: |
| `insert_batch100` | 179.49 | 178.05 | 22.19 | 15.52 |
| `point_lookup` | 242.46 | 182.85 | 7.68 | 29.60 |
| `range_scan_4096` | 254.46 | 178.26 | 6.65 | 0.92 |
| `table_count` | 196.33 | 176.60 | 2.64 | 4.26 |
| `mixed_threads8_batch100_range4096` | 394.53 | 273.86 | 64.52 | 31.08 |

Conclusion: ADB is ahead of H2DB overall in throughput and allocation/op. `mixed` throughput is `1.35x` H2, but p99 and heap peak are still higher. The next optimization focus remains mixed tail latency, temporary object retention during commits, and retained heap from caches.

## Allocation / Heap Metric Rules

`AdbBenchmarkMain` emits these allocation fields:

| Field | Meaning |
| --- | --- |
| `allocation.supported` | Whether current JVM supports thread allocation accounting |
| `allocation.totalBytes` | Allocated bytes during the measured window |
| `allocation.bytesPerOperation` | Average allocated bytes per operation |

Heap fields:

| Field | Meaning |
| --- | --- |
| `heap.sampling.supported` | Whether in-process heap sampling is enabled |
| `heap.usedBeforeBytes` / `heap.usedAfterBytes` | Used heap before and after measured window |
| `heap.usedPeakBytes` | Used heap peak observed during measured window |
| `heap.usedDeltaBytes` / `heap.usedPeakDeltaBytes` | End/peak change relative to before |
| `heap.checkpoint.initialBytes` | Used heap at benchmark path start |
| `heap.checkpoint.afterPrepareBytes` | Used heap after schema / store / benchmark data preparation |
| `heap.checkpoint.afterWarmupBytes` | Used heap after warmup and before measurement |
| `heap.checkpoint.afterMeasureBytes` | Used heap after measurement |
| `heap.stage.prepareDeltaBytes` | Prepare-stage heap change |
| `heap.stage.warmupDeltaBytes` | Warmup-stage heap change |
| `heap.stage.measureDeltaBytes` | End-of-measure heap change relative to warmup |
| `heap.stage.measurePeakDeltaBytes` | Measured-window peak change relative to warmup |

Rules:

- `allocation.bytesPerOperation` and heap peak are different metrics; do not mix them.
- Short throughput runs are sensitive to JVM state and load phase. Release decisions should use multi-run medians or longer windows.
- H2 150k-row preparation can be slow. Do not shrink parameters for only one side because a command takes longer.

## LDB API Integration Memory

LDB snapshot APIs that ADB has adopted or should retain:

| API | ADB usage |
| --- | --- |
| `ReadSession.countClosed(begin,end)` | Low-allocation range count over closed interval `[begin,end]` |
| `ReadSession.scanClosed(begin,end, visitor)` | Scan / range count / allocation boundary; view is callback-scoped |
| `SnapshotCursor.countRemaining()` | Earlier range-count fast path |
| `keyView()` / `valueView()` | Replaces `key()` / `value()` to avoid per-row byte[] copies |
| `LDB.get(keys, visitor)` | Low-allocation dense/batch point lookup |
| `LDB.get(keys, readOptions, visitor)` | Use when explicit snapshot/read options are needed |

Key constraints:

- `seek(byte[], byte[])` is `[begin,end)`.
- `seekClosed(byte[], byte[])` is `[begin,end]`.
- Visitor `Slice value` is valid only during the callback; callers must copy when caching row/value data.
- If the caller only counts, checks hits, or decodes immediately, prefer visitor over materialized `List<byte[]>`.

ADB-side multi-get visitor trial result:

| workload | ops/s | p99 us | alloc bytes/op | heap peak MB | measure peak delta MB |
| --- | ---: | ---: | ---: | ---: | ---: |
| `alloc_multiget_materialized` | 25862.07 | 105 | 66176 | 227.99 | 100.48 |
| `alloc_multiget_visitor` | 30000.00 | 88 | 56807 | 192.82 | 72.70 |

Conclusion: The ADB-side visitor wiring improved throughput by about `+16.0%` and allocation/op by about `-14.2%` in the short run. The reduction is smaller than the LDB-local benchmark's about `-26.4%` because the ADB benchmark still includes key-list construction, the `DbStore` adapter, and benchmark accounting overhead.

## Positive Optimization History to Preserve

Keep and continue testing these positive optimizations in the independent project:

1. `TxnMap2.latestCommittedTs()` uses the real latest committed watermark rather than the timestamp generator's advanced value.
2. Point-lookup latest committed column cache uses a store-derived cache epoch; ordinary commits no longer invalidate it, while restore/content replacement still does.
3. Segment range count is enabled by default with a metadata-completeness guard; old or incomplete metadata falls back to raw visible counting.
4. After segment metadata commits, refresh segment cache before row-count cache.
5. Range count uses `ReadSession` / `scanClosed` / `countClosed` to reuse read views and avoid opening a cursor per batch.
6. Wide range count and mixed allocation sources have shifted from per-row key/value copies toward row materialization, cache, and temporary commit objects.
7. Whether `asyncWriteCombining` is enabled by default must follow the final independent-project code. Mixed high-concurrency write profiles must record whether it is enabled.

## Documents to Carry Over

| Document | Purpose |
| --- | --- |
| `docs/user-guide.md` / `.en.md` | Quick usage, JDBC URL, benchmark parameters, metric definitions |
| `docs/adb-performance-benchmark.md` / `.en.md` | Historical optimization rounds, performance evidence, reproduction commands |
| `docs/adb-standalone-split-design.md` / `.en.md` | Standalone design, module boundary, rollback strategy |
| `docs/adb-production-mvp-hardening-plan.md` / `.en.md` | Production gates, release evidence, long-run stress requirements |
| This document | Test data and rule index for the independent project handoff |

## Independent Project Takeover Checklist

1. Confirm `gradle.properties` uses `h2db_version=2.3.0` and `ldb_version=0.12.0-SNAPSHOT`, or a later explicitly chosen version.
2. Run `dependencyInsight --dependency vexra-ldb --configuration runtimeClasspath` and confirm the local snapshot is used.
3. Run core unit tests and H2 plugin integration tests.
4. Run `AdbBenchmarkMainTest` and LDB adapter tests, confirming the benchmark still emits allocation and heap fields.
5. Run one fixed ADB vs H2DB short benchmark and compare it with this document.
6. Check that the independent `vexra-adb` compile/runtime classpath does not contain `vexra-client`, `vexra-server`, `vexra-grpc`, `vexra-proto`, or `vexra-common`.
7. Confirm Raft/region-node capabilities exist only in `vexra-adb-raft`.
8. Run UTF-8 / U+FFFD checks for Chinese documents.

## Remaining Priorities

| Priority | Work |
| --- | --- |
| P0 | Restore core single-node tests and benchmark runnability in the independent project |
| P0 | Keep `jdbc:adb:*` URL, H2 plugin, and `adb_table` table provider compatibility |
| P1 | Split heap retention sources for latest-column cache, segment/row-count cache, and store block/cache |
| P1 | Use multi-get visitor in real dense/batch point-lookup hot paths without retaining `Slice` outside callbacks |
| P1 | Establish multi-run median benchmarks and longer release-profile windows |
| P2 | Wire trial production gate, doctor, backup/restore, and upgrade-plan evidence into the independent repository release flow |
