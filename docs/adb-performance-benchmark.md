# ADB 性能基线报告

## 背景

用户反馈当前 ADB 性能明显偏低，需要确认瓶颈是否来自 `vexra-ldb` 本体，还是来自
`JDBC -> h2db -> ADB table engine -> MVCC / lock / index / commitTs -> LdbStore`
这一整条 SQL 执行链路。

本报告记录 2026-06-21 的本地短跑基线。测试只覆盖文件型 `ldb`，不覆盖 `mem` 模式。

## 测试环境

| 项 | 值 |
| --- | --- |
| 日期 | 2026-06-21 |
| ADB 模块 | `vexra-adb` |
| ldb 版本 | `0.10.0` |
| 行数 | 5000 |
| 预热操作数 | 300 |
| 正式操作数 | 3000 |
| range size | 32 |
| 并发 | 单线程 |
| 持久化模式 | `jdbc:adb:ldb:*` 或本地 `LdbStore` |

## 基准工具

`AdbBenchmarkMain` 支持两种模式：

| 模式 | 路径 | 用途 |
| --- | --- | --- |
| `jdbc` | `JDBC -> h2db -> ADB table engine -> LdbStore` | 衡量真实 SQL/JDBC 路径成本 |
| `store` | `AdbBenchmarkMain -> LdbStore` | 衡量本地 store 封装基线，排除 SQL/table engine 成本 |

`jdbc` 模式还支持 `--transactionBatchSize`，用于区分单条 auto-commit 和批量事务提交成本。

## 本轮结果

| 模式 | workload | transactionBatchSize | throughput ops/s | p50 us | p95 us | p99 us | max us | 结果文件 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `jdbc` | `insert` | 1 | 83.80 | 11615 | 16909 | 19506 | 69744 | `vexra-adb/build/adb-benchmark/insert.properties` |
| `jdbc` | `point_lookup` | 1 | 228.80 | 4314 | 5790 | 6911 | 11724 | `vexra-adb/build/adb-benchmark/point_lookup.properties` |
| `jdbc` | `range_scan` | 1 | 72.80 | 13308 | 23659 | 27661 | 39264 | `vexra-adb/build/adb-benchmark/range_scan.properties` |
| `jdbc` | `mixed` | 1 | 154.35 | 4376 | 19425 | 24190 | 43916 | `vexra-adb/build/adb-benchmark/mixed.properties` |
| `jdbc` | `insert` | 100 | 189.12 | 4245 | 8380 | 15551 | 102062 | `vexra-adb/build/adb-benchmark/jdbc_insert_batch100.properties` |
| `jdbc` | `mixed` | 100 | 470.15 | 699 | 8619 | 11639 | 19240 | `vexra-adb/build/adb-benchmark/jdbc_mixed_batch100.properties` |
| `store` | `insert` | 不适用 | 130434.78 | 5 | 18 | 41 | 706 | `vexra-adb/build/adb-benchmark/store_insert.properties` |
| `store` | `point_lookup` | 不适用 | 200000.00 | 3 | 9 | 28 | 388 | `vexra-adb/build/adb-benchmark/store_point_lookup.properties` |
| `store` | `range_scan` | 不适用 | 2439.02 | 322 | 772 | 1480 | 7223 | `vexra-adb/build/adb-benchmark/store_range_scan.properties` |
| `store` | `mixed` | 不适用 | 13392.86 | 2 | 352 | 612 | 4615 | `vexra-adb/build/adb-benchmark/store_mixed.properties` |

## 结论

1. 本轮数据不支持“ldb 本体很慢”的判断。`store` 模式写入约 130k ops/s、点查约
   200k ops/s，明显高于 JDBC SQL 路径。
2. ADB 当前低吞吐主要来自 SQL/JDBC/table-engine 执行链路。即使把 JDBC 写入从
   auto-commit 改为 batch 100，`insert` 也只从约 84 ops/s 提升到约 189 ops/s。
3. `mixed` 在 batch 100 下从约 154 ops/s 提升到约 470 ops/s，说明提交频率有影响，
   但不是唯一瓶颈。
4. 下一轮优化应优先定位 ADB table engine 的每行执行成本、MVCC/index 更新成本、
   事务时间戳与锁路径成本，而不是先优化 ldb。

## 后续优化靶点

| 优先级 | 靶点 | 验证方式 |
| --- | --- | --- |
| P0 | 给 JDBC/table engine 路径加分段耗时统计 | 在 insert / point lookup / range scan 中输出 parser、planner、table engine、store、commit 阶段耗时 |
| P0 | 优化 batch 写入路径 | 支持一次 SQL 事务内批量写入时减少 per-row commitTs、index、row-count 重复成本 |
| P1 | 点查绕过不必要扫描和对象分配 | 用 allocation profiling 与 p50/p99 对照验证 |
| P1 | range scan 避免 SQL COUNT 路径上的额外 materialization | 对比 `LdbStore` scan 与 SQL scan 的行迭代次数、对象创建数 |
| P1 | 增加多线程压测模式 | 验证单线程优化后是否出现锁竞争或 store 写放大 |

## 复现命令

store mixed 基线：

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

JDBC mixed batch 100：

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
