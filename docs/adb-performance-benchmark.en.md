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

## Next Optimization Targets

| Priority | Target | Verification |
| --- | --- | --- |
| P0 | Add segmented timing to the JDBC/table-engine path | Report parser, planner, table engine, store, and commit timing for insert / point lookup / range scan |
| P0 | Optimize batched writes | Reduce repeated per-row commitTs, index, and row-count work within one SQL transaction |
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
