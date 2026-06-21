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
| `jdbc` | `insert` | 100，优化后 | 242.99 | 3135 | 5829 | 9776 | 123811 | `vexra-adb/build/adb-benchmark/jdbc_insert_batch100_opt1.properties` |
| `jdbc` | `mixed` | 100，优化后 | 500.08 | 702 | 7923 | 12301 | 23370 | `vexra-adb/build/adb-benchmark/jdbc_mixed_batch100_opt1.properties` |
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

## 第一轮优化结果

第一轮优化做了两个低风险改动：

1. benchmark 接入 SQL/table-engine 诊断聚合，输出 `sqlDiagnostics.*` 字段。
2. `TxnMap2.put/putIfAbsent/delete` 复用 table/index 层已经读取过的旧可见版本，避免进入
   `Transaction2.put/delete` 后再重复打开版本扫描器。

前后对比：

| workload | 优化前 throughput ops/s | 优化后 throughput ops/s | 变化 |
| --- | ---: | ---: | ---: |
| `insert` batch 100 | 189.12 | 242.99 | +28.5% |
| `mixed` batch 100 | 470.15 | 500.08 | +6.4% |

新的 `sqlDiagnostics.operationStats.*` 显示，`mixed` batch 100 正式窗口中主要入口为：

| 操作 | 次数 | 平均耗时 us | 总耗时 ms |
| --- | ---: | ---: | ---: |
| `ADB_TABLE_PRIMARY_FIND ADB_BENCH` | 3000 | 699 | 2098 |
| `ADB_TABLE_ADD_ROW ADB_BENCH` | 300 | 2213 | 664 |

该结果说明重复 scan 优化对写入有效，但 SQL/JDBC、H2 执行、commit 扫描 txn ref 和
table-engine 边界仍是主要瓶颈。

## 后续优化靶点

| 优先级 | 靶点 | 验证方式 |
| --- | --- | --- |
| P0 | 普通 SQL INSERT 自动命中 bulk 入口 | 需要 h2db 暴露表级 bulk insert hook；ADB 侧保持 `bulkInsertAppendRows` 作为可调用入口，待 h2db hook 后验证普通 `jdbc` insert > 3000 ops/s |
| P0 | 细化 commit 阶段耗时统计 | 区分 txn ref 扫描、intent 读取、committed version 写入、meta 写入和底层 write batch |
| P0 | 优化 batch 写入路径 | 支持一次 SQL 事务内批量写入时减少 per-row writeBatch、txn ref 扫描和 row-count 重复成本 |
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

## 第三轮 JDBC bulk insert 结果

第三轮新增 JDBC 连接下的 ADB bulk insert 路径。benchmark 仍然通过
`jdbc:adb:ldb:*` 打开数据库，并通过 H2 创建 ADB 表；正式插入阶段复用当前 H2
`SessionLocal` 调用 ADB table bulk API，从而避开 H2 SQL executor 对多 values
insert 的逐行 `Table.addRow` 调度，同时保留 JDBC transaction event 的提交边界。

当前三条线结果如下：

| 模式 | workload | operations | batch | throughput ops/s | p99 us | 结果文件 | 说明 |
| --- | --- | ---: | ---: | ---: | ---: | --- | --- |
| `store` | `insert` | 3000 | 不适用 | 130434.78 | 41 | `vexra-adb/build/adb-benchmark/store_insert.properties` | 本地 store 封装基线 |
| `txn` | `insert` | 3000 | 3000 | 63829.79 | 25 | `vexra-adb/build/adb-benchmark/txn_insert_goal.properties` | ADB 本地事务/MVCC/commit 路径 |
| `jdbc_bulk` | `insert` | 100000 | 5000 | 357142.86 | 6 | `vexra-adb/build/adb-benchmark/jdbc_bulk_insert_goal_100k.properties` | JDBC 连接 + ADB table bulk API |

`jdbc_bulk` 已超过硬目标 `3000 ops/s`，也明显超过期望余量 `5000 ops/s`。当前
fast path 保持 local-only：带 region commit coordinator 的表会拒绝 bulk fast path，
避免绕过分布式提交；重复主键仍会报错。

本轮增量已支持本地二级索引表使用 `bulkInsertAppendRows`：批量写 row 前先完成主键校验，
再把 secondary index key 登记到同一个 ADB 事务 write set 中，随用户事务一起
commit/rollback。覆盖用例包括非唯一二级索引查询、唯一二级索引批内冲突拒绝，以及
bulk 后 rollback 不留下 row 或 index entry。

当前 `h2db:2.3.0` 的 `org.h2.command.dml.Insert` 对 `VALUES` 语句仍逐行调用
`Table.addRow(SessionLocal, Row)`，`org.h2.table.Table` 也没有公开表级批量插入回调。
因此普通用户执行 `INSERT INTO ... VALUES (...), (...)` 还不能只靠 ADB 插件侧自动路由到
`bulkInsertAppendRows`。要完成普通 SQL 自动 bulk，需要 h2db 新增一个保持触发器、约束、
generated column、`ON DUPLICATE KEY` 和 delta table 语义的表级 bulk insert SPI；ADB 已保留
可被该 SPI 调用的 bulk 表入口。

JDBC bulk insert 复现命令：

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

## 第二轮插入优化结果

第二轮围绕 insert 热路径继续优化：

1. 本地单机事务在没有 region commit coordinator 时，不再逐行持久化 intent，而是在 commit 阶段一次底层 write batch 写入 committed version 和 meta。
2. append-only 主键插入维护进程内 rowId 上界 hint；当新 rowId 明确大于已提交上界且当前事务未写过同 key 时，跳过主键唯一性 committed 扫描。
3. 同一 append fast path 下跳过行锁 HashMap/等待路径；随机插入、重复 key、更新、删除和分布式提交仍保留原锁与完整检查。
4. benchmark 支持多 values insert、`statementBatchSize`、`txn` 模式，以及表参数 `adb.sql.diagnostics=false`。

当前可复现结果：

| 模式 | workload | batch | throughput ops/s | p99 us | 结果文件 | 说明 |
| --- | --- | ---: | ---: | ---: | --- | --- |
| `jdbc` | `insert` | 3000 | 2752.29 | 363 | `vexra-adb/build/adb-benchmark/jdbc_insert_goal_fastpath_reuse.properties` | SQL/JDBC/table-engine 路径最佳短跑结果，仍未超过 3000 |
| `txn` | `insert` | 3000 | 63829.79 | 25 | `vexra-adb/build/adb-benchmark/txn_insert_goal.properties` | ADB 本地事务/MVCC/commit 路径，已超过 3000 ops/s 目标 |

结论：ADB 本地事务写入能力已经超过 `3000 ops/s`，当前没有证据表明 ldb 或 ADB MVCC/commit 是 insert 的主要瓶颈。剩余不足集中在 `JDBC -> h2db SQL parser/executor -> TableEngine.addRow` 的逐行调度边界；若要求 JDBC insert 也稳定超过 `3000 ops/s`，下一阶段需要做真正的 SQL bulk 写入入口，而不是继续只优化底层 store。

事务层 insert 复现命令：

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
