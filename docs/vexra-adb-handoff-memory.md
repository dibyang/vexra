# vexra-adb 独立项目交接记忆

## 交接范围

本文用于把 `vexra-adb` 从当前 `vexra` 仓库剥离为独立项目后的测试数据、测试规则、性能口径和历史约束交给新的 `vexra-adb` 项目维护。它记录的是 2026-07-14 前在本仓库内完成的 ADB 侧验证记忆，方便独立仓库接手后继续复现、对比和提交。

当前原仓库状态已经进入剥离阶段：`vexra-adb` 源码目录在 `vexra` 工作区中被移除，`vexra-adb-raft` 留在原仓库承载 Raft 扩展。独立项目应继续发布原坐标 `net.xdob.vexra:vexra-adb`，Java 包名和 JDBC 行为保持兼容。

## 基础版本和边界

| 项 | 交接值 / 规则 |
| --- | --- |
| h2db 版本 | `net.xdob.h2db:h2db:2.3.0` |
| ldb 版本 | `net.xdob.vexra:vexra-ldb:0.12.0-SNAPSHOT`，优先使用本地 Maven 仓库快照 |
| ADB 坐标 | `net.xdob.vexra:vexra-adb`，独立后继续沿用 |
| JDBC 前缀 | `jdbc:adb:mem:`、`jdbc:adb:ldb:`、`jdbc:adb:tcp://...` 必须兼容 |
| 默认重点 | 文件型 `ldb`，不要把 `mem` 模式作为性能判断口径 |
| h2db 关系 | 直接复用 h2db 的 SQL parser / JDBC / Server / tools，通过插件注册 URL 前缀和 table provider，不再维护 H2 衍生代码 |
| vexra-ldb 边界 | 视为上游依赖。ADB 侧不得静默修改 `vexra-ldb`；需要新能力时先形成需求或获得明确授权 |
| Raft 边界 | 核心 `vexra-adb` 不依赖 Vexra Raft runtime；Raft 能力由 `vexra-adb-raft` 承接 |

## 必须迁移的测试入口

独立项目至少保留以下 Gradle task 或等价命令：

```powershell
.\gradlew.bat test
.\gradlew.bat adbBenchmark
.\gradlew.bat adbRuntimeDist
.\gradlew.bat adbReleaseProfile
```

旧模块中的高价值定向测试需要迁移并保持可运行：

| 测试类别 | 代表测试 |
| --- | --- |
| H2 插件和 URL 前缀 | `AdbJdbcUrlPrefixProviderTest`、`AdbTableProviderIntegrationTest`、`AdbH2PluginTest`、`AdbTransactionEventProviderTest` |
| JDBC/SQL Server | `AdbSqlServerMainTest`、`AdbRuntimeDistributionSmokeTest` |
| benchmark 工具 | `AdbBenchmarkMainTest` |
| LDB 适配 | `LdbVersionReadSessionTest`、`LdbVersionEntryCursorTest`、`LdbStoreReliabilityTest`、`LdbStoreMultiGetTest` |
| MVCC/可见性/缓存 | `TxnManagerVisibleRowFastPathTest`、`BoundedSeekRegressionTest`、`RowValueTest`、`RowCodecTest` |
| 事务与写批 | `AdbWriteBatchTest`、`AdbLockResolverTest`、`AdbCommittedVersionGcCleanerTest` |
| 生产门禁 | `AdbReleaseProfileRunnerTest`、`AdbReleaseReadinessGateTest`、`AdbProductionGuardTest`、`AdbTrialProductionAdmissionGateTest` |

## Benchmark 固定口径

用于和 H2DB 对比的固定短测口径：

| 参数 | 值 |
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
| SQL diagnostics | 对比测试默认 `false` |

固定 workload：

| workload 名称 | ADB 参数 |
| --- | --- |
| `insert_batch100` | `workload=insert`、`transactionBatchSize=100`、`statementBatchSize=100` |
| `point_lookup` | `workload=point_lookup` |
| `range_scan_4096` | `workload=range_scan`、`rangeSize=4096` |
| `table_count` | `workload=table_count` |
| `mixed_threads8_batch100_range4096` | `workload=mixed`、`threads=8`、`transactionBatchSize=100`、`statementBatchSize=100`、`rangeSize=4096` |

对比命令模板：

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

H2DB 对照只改 URL 和 table engine：

```powershell
"-PadbBenchmarkUrl=jdbc:h2:D:/work/java2/vexra-adb/build/adb-benchmark/<run-id>/db/h2-<case>/benchmark;DB_CLOSE_DELAY=0"
"-PadbBenchmarkTableEngine=h2"
```

## 最新 ADB vs H2DB 快照结果

依赖解析确认：`net.xdob.vexra:vexra-ldb:0.12.0-SNAPSHOT`。

结果目录：

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

内存侧：

| workload | ADB heap peak MB | H2 heap peak MB | ADB measure peak delta MB | H2 measure peak delta MB |
| --- | ---: | ---: | ---: | ---: |
| `insert_batch100` | 179.49 | 178.05 | 22.19 | 15.52 |
| `point_lookup` | 242.46 | 182.85 | 7.68 | 29.60 |
| `range_scan_4096` | 254.46 | 178.26 | 6.65 | 0.92 |
| `table_count` | 196.33 | 176.60 | 2.64 | 4.26 |
| `mixed_threads8_batch100_range4096` | 394.53 | 273.86 | 64.52 | 31.08 |

结论：ADB 在吞吐和 allocation/op 上整体领先 H2DB；`mixed` 吞吐为 H2 的 `1.35x`，但 p99 和 heap peak 仍偏高。后续优化重点仍是 `mixed` 尾延迟、提交期临时对象滞留和缓存保留堆。

## Allocation / heap 指标规则

`AdbBenchmarkMain` 已输出以下 allocation 字段：

| 字段 | 含义 |
| --- | --- |
| `allocation.supported` | 当前 JVM 是否支持线程分配统计 |
| `allocation.totalBytes` | 正式测量窗口内当前线程或 worker 汇总分配字节 |
| `allocation.bytesPerOperation` | 平均每个 operation 分配字节 |

heap 字段：

| 字段 | 含义 |
| --- | --- |
| `heap.sampling.supported` | benchmark 是否启用进程内 heap 采样 |
| `heap.usedBeforeBytes` / `heap.usedAfterBytes` | 正式窗口前后已用堆 |
| `heap.usedPeakBytes` | 正式窗口观测到的已用堆峰值 |
| `heap.usedDeltaBytes` / `heap.usedPeakDeltaBytes` | 正式窗口结束/峰值相对 before 的变化 |
| `heap.checkpoint.initialBytes` | benchmark 路径开始时已用堆 |
| `heap.checkpoint.afterPrepareBytes` | schema / store / benchmark 数据准备后已用堆 |
| `heap.checkpoint.afterWarmupBytes` | warmup 后、正式测量前已用堆 |
| `heap.checkpoint.afterMeasureBytes` | 正式测量完成后已用堆 |
| `heap.stage.prepareDeltaBytes` | prepare 阶段堆变化 |
| `heap.stage.warmupDeltaBytes` | warmup 阶段堆变化 |
| `heap.stage.measureDeltaBytes` | 正式测量结束相对 warmup 的堆变化 |
| `heap.stage.measurePeakDeltaBytes` | 正式测量窗口内相对 warmup 的峰值变化 |

规则：

- `allocation.bytesPerOperation` 和 heap peak 不是同一指标，不要混用。
- 短测吞吐容易受 JVM 状态和装载阶段影响，发布判断要使用多轮中位数或更长窗口。
- H2 对照的 15 万行准备阶段可能很慢，不要因为命令耗时长就缩小单边参数。

## LDB API 接入记忆

ADB 已试接或需要保留的 LDB snapshot API：

| API | ADB 用法 |
| --- | --- |
| `ReadSession.countClosed(begin,end)` | range count 低分配路径，闭区间 `[begin,end]` |
| `ReadSession.scanClosed(begin,end, visitor)` | scan / range count / allocation boundary，visitor 中 view 只在回调内有效 |
| `SnapshotCursor.countRemaining()` | 早期 range count fast path |
| `keyView()` / `valueView()` | 替代 `key()` / `value()`，避免每行 byte[] 拷贝 |
| `LDB.get(keys, visitor)` | dense/batch point lookup 低分配路径 |
| `LDB.get(keys, readOptions, visitor)` | 需要指定 snapshot/read options 时使用 |

关键约束：

- `seek(byte[], byte[])` 是 `[begin,end)`。
- `seekClosed(byte[], byte[])` 是 `[begin,end]`。
- visitor 中的 `Slice value` 只在当前回调有效；需要缓存 row/value 时必须复制。
- 如果只是 count、判断命中或立即 decode，优先用 visitor，而不是 `List<byte[]>` materialized 返回。

ADB 上层 multi-get visitor 试接结果：

| workload | ops/s | p99 us | alloc bytes/op | heap peak MB | measure peak delta MB |
| --- | ---: | ---: | ---: | ---: | ---: |
| `alloc_multiget_materialized` | 25862.07 | 105 | 66176 | 227.99 | 100.48 |
| `alloc_multiget_visitor` | 30000.00 | 88 | 56807 | 192.82 | 72.70 |

结论：ADB 侧 visitor 接入在短测中吞吐约 `+16.0%`，allocation/op 约 `-14.2%`。下降幅度低于 LDB 本地 benchmark 的约 `-26.4%`，因为 ADB 侧仍包含 key 列表构造、`DbStore` 适配层和 benchmark 统计开销。

## 性能优化历史保留点

以下正向优化应在独立项目中保留并继续测试：

1. `TxnMap2.latestCommittedTs()` 使用真实 latest committed watermark，避免把时间戳生成器前移值当作已提交水位。
2. 点查 latest committed column cache 使用 store-derived cache epoch，普通提交不误杀缓存，restore 或底层内容替换时失效。
3. 默认启用带完整性保护的 segment range count；旧库或元数据不完整时回退 raw visible count。
4. segment 元数据提交后先刷新 segment cache，再刷新 row count cache。
5. range count 使用 `ReadSession` / `scanClosed` / `countClosed` 复用读视图，避免每批新建 cursor。
6. 宽 range count 和 mixed 的 allocation 大头已经从每行 key/value 拷贝转向 row materialization、cache 和提交期临时对象。
7. `asyncWriteCombining` 是否默认开启以独立项目最终代码为准；做 mixed 高并发写 profile 时必须单独记录是否启用。

## 必须随交接保留的文档

| 文档 | 用途 |
| --- | --- |
| `docs/user-guide.md` / `.en.md` | 快速使用、JDBC URL、benchmark 参数和指标说明 |
| `docs/adb-performance-benchmark.md` / `.en.md` | 历史优化轮次、性能证据和复现命令 |
| `docs/adb-standalone-split-design.md` / `.en.md` | 独立化设计、模块边界和回滚策略 |
| `docs/adb-production-mvp-hardening-plan.md` / `.en.md` | 生产级门禁、发布证据和长稳压测要求 |
| 本文档 | 独立项目接手时的测试数据和规则索引 |

## 独立项目接手清单

1. 确认 `gradle.properties` 使用 `h2db_version=2.3.0`、`ldb_version=0.12.0-SNAPSHOT` 或后续明确升级版本。
2. 运行 `dependencyInsight --dependency vexra-ldb --configuration runtimeClasspath`，确认使用本地快照。
3. 运行核心单测和 H2 插件集成测试。
4. 运行 `AdbBenchmarkMainTest` 和 LDB 适配测试，确认 benchmark 工具仍能产出 allocation/heap 字段。
5. 跑一轮 ADB vs H2DB 固定短测，并与本文最新结果对照。
6. 检查 `vexra-adb` 独立项目 compile/runtime classpath 不包含 `vexra-client`、`vexra-server`、`vexra-grpc`、`vexra-proto`、`vexra-common`。
7. 确认 Raft/region node 能力只在 `vexra-adb-raft` 中存在。
8. 对中文文档执行 UTF-8 / U+FFFD 检查。

## 当前未完成/后续优先级

| 优先级 | 工作 |
| --- | --- |
| P0 | 独立项目先恢复核心单机测试和 benchmark 可运行性 |
| P0 | 保持 `jdbc:adb:*` URL、H2 plugin、`adb_table` table provider 兼容 |
| P1 | 把 latest-column cache、segment/row-count cache、store block/cache 的 heap 保留来源拆开 |
| P1 | 在真实 dense/batch point lookup 热路径使用 multi-get visitor，但不得跨回调持有 `Slice` |
| P1 | 建立多轮中位数 benchmark 和更长窗口 release profile |
| P2 | 将 trial production gate、doctor、backup/restore、upgrade plan 的 evidence 接入独立仓库发布流程 |
