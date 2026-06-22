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

## 第五轮多线程混合负载诊断

本轮先补齐 `jdbc` benchmark 的 `threads` 参数和 `concurrency.*` 输出字段，用同一套
`mixed` workload 验证单机多 JDBC connection 下的伸缩性。测试仍使用文件型
`jdbc:adb:ldb:*`，`rows=5000`、`warmupOperations=300`、`operations=3000`、
`transactionBatchSize=100`。

| threads | throughput ops/s | p50 us | p95 us | p99 us | max us | per-thread ops/s | 结果文件 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 1351.35 | 578 | 1667 | 2656 | 7378 | 1351.35 | `vexra-adb/build/adb-benchmark/jdbc_mixed_threads_1.properties` |
| 2 | 1110.70 | 956 | 2153 | 3878 | 9316 | 555.35 | `vexra-adb/build/adb-benchmark/jdbc_mixed_threads_2.properties` |
| 4 | 1315.21 | 1458 | 3224 | 5816 | 10465 | 328.80 | `vexra-adb/build/adb-benchmark/jdbc_mixed_threads_4.properties` |
| 8 | 1515.15 | 2338 | 5159 | 8046 | 19585 | 189.39 | `vexra-adb/build/adb-benchmark/jdbc_mixed_threads_8.properties` |

结论：当前 mixed workload 在 8 线程下只比 1 线程提升约 12.1%，但 p99 从 2656us
升至 8046us，说明多连接并发主要放大了共享路径延迟，而不是线性提升吞吐。
`sqlDiagnostics.*` 也显示 `ADB_TABLE_ADD_ROW`、`ADB_TABLE_PRIMARY_FIND` 和
`ADB_TABLE_RANGE_COUNT_FAST` 的平均延迟随线程数明显升高，其中 8 线程下
`ADB_TABLE_ADD_ROW` 最大延迟达到 64ms。下一阶段应优先细分 commit / row-count /
主键查找 / range count 的锁等待和 store 写入时间，而不是继续单纯提高客户端线程数。

复现命令示例：

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

## row-count base snapshot 读后压实结果

本轮继续深化 row-count 缓存：当冷启动 `getBaseRowCount` 扫描到较多
row-count delta 时，读路径会用本次扫描得到的精确行数写入一条新的
`VersionRowCountKey` base snapshot。后续冷启动会从该 base snapshot 的
`commitTs` 之后继续扫描 delta，从而减少 delta meta 重扫。

实现约束：

1. 只写新的 base snapshot，不删除旧 delta，避免并发提交场景下 `deleteRange`
   误删较新的 delta。
2. 压实是 best-effort 优化，写 snapshot 失败不会影响本次 `COUNT(*)` 结果。
3. 默认阈值为 `vexra.adb.rowCount.compactDeltaThreshold=256`；设置为 `0` 或负数可关闭。
4. benchmark 可通过 `-PadbRowCountCompactDeltaThreshold=...` 控制阈值。

验证命令 `.\gradlew.bat :vexra-adb:test --rerun-tasks` 已通过；新增测试覆盖：
首次 reopen count 触发 `ADB_ROW_COUNT_BASE_COMPACT`，再次 reopen count 不再触发压实，证明后续读取已从新 base snapshot 开始。

mixed 8 线程、阈值 16 的结果：

| 模式 | workload | threads | operations | throughput ops/s | p50 us | p95 us | p99 us | 结果文件 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `jdbc` | `mixed` | 8 | 3000 | 1221.00 | 2373 | 11121 | 15080 | `vexra-adb/build/adb-benchmark/jdbc_mixed_rowcount_compact_threads_8.properties` |

诊断结论：

- 本轮 benchmark 中 `ADB_ROW_COUNT_BASE_COMPACT` 记录 1 次，耗时约 3268 us。
- `ADB_ROW_COUNT_BASE_SCAN` 仍只记录 1 次，说明 single-flight 仍有效。
- 该优化主要面向“delta 很多后的重启 / 首次 COUNT”场景，不应期望显著提升在线 mixed 主窗口吞吐。
  当前 mixed 仍主要受 `ADB_TABLE_POINT_LOOKUP_FAST`、`ADB_TABLE_ADD_ROW`、
  `ADB_TABLE_PRIMARY_FIND` 和 `ADB_TABLE_RANGE_COUNT_FAST` 影响。

复现命令：

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

## prepared point lookup Value 数组复用结果

本轮继续收窄 `SELECT col FROM table WHERE ID = ?` prepared fast path 的对象边界：

1. `AdbPreparedPointLookupPlan` 的 decoded column cache 不再在命中时复制 `Value[]`。
2. cache miss 后也直接把 `RowCodec.decodeColumns(...)` 返回的 `Value[]` 交给只读的
   `AdbSimpleResultSet`，避免 decode 后再复制一次。
3. `AdbSimpleResultSet` 不修改 `Value[]`，H2 `Value` 在该路径按不可变值对象使用，因此该优化不改变查询语义。

验证命令 `.\gradlew.bat :vexra-adb:test --rerun-tasks` 已通过。

可复现结果：

| 模式 | workload | threads | operations | throughput ops/s | p50 us | p95 us | p99 us | 结果文件 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `jdbc` | `point_lookup` | 1 | 3000 | 2373.42 | 332 | 843 | 1252 | `vexra-adb/build/adb-benchmark/point_lookup_value_array_reuse.properties` |
| `jdbc` | `mixed` | 8 | 3000 | 1197.60 | - | - | 15177 | `vexra-adb/build/adb-benchmark/jdbc_mixed_value_array_reuse_threads_8.properties` |

结论：

- 纯 prepared point lookup 明显受益，`ADB_TABLE_POINT_LOOKUP_FAST` 平均约 416 us，p99 为 1252 us。
- mixed 8 线程没有同步改善，说明综合负载仍由 `ADB_TABLE_PRIMARY_FIND`、`ADB_TABLE_ADD_ROW`、
  `ADB_TABLE_RANGE_COUNT_FAST` 和外层 JDBC/table-engine 边界主导。
- 下一步应继续处理 primary find 的可见性解析与 Row 构造边界，或者推进普通 JDBC insert 的写入入口优化。

point lookup 复现命令：

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

mixed 8 线程复现命令：

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

## point lookup / primary find 详细诊断开关

本轮为 point lookup 和 primary find 增加了按需启用的细粒度 phase：

- `ADB_POINT_LOOKUP_VISIBLE_ROW`：prepared point lookup 获取 MVCC 可见行。
- `ADB_POINT_LOOKUP_RESULT_BUILD`：prepared point lookup 构造 fast-path ResultSet。
- `ADB_PRIMARY_FIND_VISIBLE_ROW`：H2 primary find 点查路径获取 MVCC 可见行。
- `ADB_PRIMARY_FIND_ROW_CACHE_HIT` / `ADB_PRIMARY_FIND_ROW_CACHE_MISS`：primary find 解码 row cache 命中情况。

这些 phase 会调用 SQL diagnostic recorder，默认关闭，避免在高频点查路径上引入同步统计开销。
如需在 benchmark 中打开，使用 `-PadbBenchmarkDetailedDiagnostics=true`；运行时也可以设置
`-Dvexra.adb.sql.diagnostic.detail=true`。

默认诊断关闭的 mixed 8 线程结果：

| 模式 | workload | threads | operations | throughput ops/s | p99 us | 结果文件 |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| `jdbc` | `mixed` | 8 | 3000 | 1300.95 | 13488 | `vexra-adb/build/adb-benchmark/jdbc_mixed_detail_toggle_threads_8.properties` |

详细诊断开启的 mixed 8 线程结果：

| 模式 | workload | threads | operations | throughput ops/s | p99 us | 结果文件 |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| `jdbc` | `mixed` | 8 | 3000 | 1257.33 | 13707 | `vexra-adb/build/adb-benchmark/jdbc_mixed_detail_on_threads_8.properties` |

详细诊断结论：

- `ADB_TABLE_POINT_LOOKUP_FAST` 平均约 2360 us，其中 `ADB_POINT_LOOKUP_VISIBLE_ROW`
  平均约 259 us，`ADB_POINT_LOOKUP_RESULT_BUILD` 平均约 16 us，
  `ADB_POINT_LOOKUP_DECODE_CACHE_MISS` 平均约 8 us。
- `ADB_TABLE_PRIMARY_FIND` 平均约 3292 us，其中 `ADB_PRIMARY_FIND_VISIBLE_ROW`
  平均约 1404 us。
- 因此下一步 point lookup / primary find 的高价值优化不应继续优先压列值 decode，
  而应转向减少 H2/JDBC/table-engine 外层调用边界、primary find 可见性解析和 Row/ResultSet 对象边界。

默认 mixed 复现命令：

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

详细诊断 mixed 复现命令：

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

## row-count 冷启动 single-flight 优化结果

上一轮 mixed 8 线程报告显示 `ADB_ROW_COUNT_CACHE_MISS` / `ADB_ROW_COUNT_BASE_SCAN`
在并发冷启动时出现 8 次，每次都扫描同一张表的 row-count 基线和 delta meta。
本轮将 `TxnManager.getCachedBaseRowCount` 改为按表 single-flight 加载：

1. 同一 `TabId` 首次 miss 时只有一个线程执行 `getBaseRowCount`。
2. 其它并发线程等待同一张表的加载完成后直接读取缓存，并记录
   `ADB_ROW_COUNT_CACHE_WAIT_HIT`。
3. 已有的 commit 后 delta 刷新和 truncate/epoch invalidation 仍复用
   `rowCountCache`，不改变 row-count 可见性语义。

新增集成测试 `concurrentTableCountLoadsBaseRowCountOnce` 使用 8 个并发 JDBC 连接同时执行
`SELECT COUNT(*) FROM TEST`，验证全部返回正确行数，并且 `ADB_ROW_COUNT_CACHE_MISS`
只记录 1 次。

验证与结果：

| 模式 | workload | threads | operations | throughput ops/s | p50 us | p95 us | p99 us | 结果文件 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `jdbc` | `mixed` | 8 | 3000 | 1343.48 | 2241 | 10603 | 13751 | `vexra-adb/build/adb-benchmark/jdbc_mixed_rowcount_singleflight_threads_8.properties` |

对比上一轮 mixed 8 线程 `1148.11 ops/s`、p99 `15640us`，本轮吞吐提升约 17%，
p99 降低约 12%。phase 明细中 `ADB_ROW_COUNT_CACHE_MISS` 从 8 次降为 1 次，
`ADB_ROW_COUNT_CACHE_WAIT_HIT` 记录 7 次，说明冷启动重复扫描已被合并。

剩余瓶颈仍主要集中在：

- `ADB_TABLE_POINT_LOOKUP_FAST`：总耗时最高，下一步应继续压缩 JDBC fast path 到行对象边界的开销。
- `ADB_TABLE_ADD_ROW` / `ADB_TABLE_PRIMARY_FIND`：写入和主键查找入口仍是 mixed 中的高延迟阶段。
- `ADB_TABLE_RANGE_COUNT_FAST`：内部 count-only 已优化，但外层 table-engine/JDBC 边界仍有固定开销。

复现命令：

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

## 第六轮关键路径阶段诊断

本轮在 SQL 诊断 recorder 中新增 `sqlDiagnostics.phaseStats.*`，与既有
`operationStats` 并列输出。阶段统计只保存阶段名、次数、总耗时、平均耗时和最大耗时，
不保存 SQL 参数或行内容。当前已覆盖：

- `ADB_COMMIT_PREPARE`、`ADB_COMMIT_ROW_COUNT_META`、`ADB_COMMIT_WRITE`、`ADB_COMMIT_POST_REFRESH`
- `ADB_ROW_COUNT_CACHE_HIT`、`ADB_ROW_COUNT_CACHE_MISS`、`ADB_ROW_COUNT_BASE_SCAN`
- `ADB_TABLE_*` 表层入口阶段，例如 `PRIMARY_FIND`、`ADD_ROW`、`POINT_LOOKUP_FAST`、`RANGE_COUNT_FAST`

8 线程 `mixed` 复测结果：

| threads | throughput ops/s | p99 us | max us | 结果文件 |
| ---: | ---: | ---: | ---: | --- |
| 8 | 1383.76 | 9399 | 15994 | `vexra-adb/build/adb-benchmark/jdbc_mixed_phase_threads_8.properties` |

关键阶段摘要：

| phase | count | avg us | max us | 说明 |
| --- | ---: | ---: | ---: | --- |
| `ADB_TABLE_POINT_LOOKUP_FAST ADB_BENCH` | 2320 | 2581 | 18000 | mixed 中最高频读路径 |
| `ADB_TABLE_RANGE_COUNT_FAST ADB_BENCH` | 648 | 2969 | 12000 | range count 仍是高延迟读路径 |
| `ADB_TABLE_PRIMARY_FIND ADB_BENCH` | 332 | 3192 | 45000 | 写入/查找会经过的主键索引路径，最大延迟最高 |
| `ADB_TABLE_ADD_ROW ADB_BENCH` | 332 | 2768 | 30000 | table-engine 写入入口仍有明显并发放大 |
| `ADB_COMMIT_WRITE` | 40 | 302 | 1422 | 本轮样本中底层 commit 写入不是主要耗时 |
| `ADB_COMMIT_PREPARE` | 40 | 844 | 10841 | prepare 阶段偶发长尾，但总量低于表/索引路径 |
| `ADB_ROW_COUNT_CACHE_HIT` | 93 | 3 | 233 | row-count cache 命中成本可以忽略 |
| `ADB_ROW_COUNT_CACHE_MISS` | 7 | 1248 | 2111 | miss 只出现在少量初始化/竞争窗口 |

结论：上一轮“多线程不线性扩展”的主要矛盾不在 `ADB_COMMIT_WRITE`，而在
`PRIMARY_FIND`、`POINT_LOOKUP_FAST`、`RANGE_COUNT_FAST` 和 `ADD_ROW` 这些表/索引入口。
下一阶段最值得做的是减少 primary find 与 point lookup 的重复解码/对象边界，以及降低 range
count 对 cursor 扫描和 H2 `COUNT` 路径的依赖；commit 写入暂时不应作为首要优化点。

## 第七轮 prepared 点查列值缓存

本轮针对 `AdbPreparedPointLookupPlan` 增加按 `rowId + commitTs` 校验的列值缓存：

1. 命中时复用已解码的 `Value[]`，但返回给 `AdbSimpleResultSet` 前仍复制数组，避免结果集共享可变数组。
2. 只缓存 `commitTs > 0` 的已提交版本；同一事务内未提交版本不进入缓存，避免 `commitTs=0` 下读到旧值。
3. 查不到行时移除对应 rowId 的缓存；update/delete 后 commitTs 或可见性变化会自动失效。
4. 新增 `ADB_POINT_LOOKUP_DECODE_CACHE_HIT/MISS` 阶段诊断，并用集成测试覆盖同一 prepared statement 下重复查询、更新和删除。

验证结果：

| workload | threads | throughput ops/s | p99 us | max us | 结果文件 |
| --- | ---: | ---: | ---: | ---: | --- |
| `point_lookup` | 1 | 1654.72 | 1606 | 2632 | `vexra-adb/build/adb-benchmark/point_lookup_decode_cache_stage.properties` |
| `mixed` | 8 | 1474.93 | 8060 | 10721 | `vexra-adb/build/adb-benchmark/jdbc_mixed_decode_cache_threads_8.properties` |

阶段摘要：

| workload | phase | count | avg us | max us |
| --- | --- | ---: | ---: | ---: |
| `point_lookup` | `ADB_POINT_LOOKUP_DECODE_CACHE_HIT` | 300 | 0 | 10 |
| `point_lookup` | `ADB_POINT_LOOKUP_DECODE_CACHE_MISS` | 2700 | 4 | 418 |
| `point_lookup` | `ADB_TABLE_POINT_LOOKUP_FAST ADB_BENCH` | 3000 | 599 | 3000 |
| `mixed` | `ADB_POINT_LOOKUP_DECODE_CACHE_HIT` | 28 | 7 | 142 |
| `mixed` | `ADB_POINT_LOOKUP_DECODE_CACHE_MISS` | 2292 | 10 | 1587 |
| `mixed` | `ADB_TABLE_POINT_LOOKUP_FAST ADB_BENCH` | 2320 | 2530 | 15000 |

结论：该缓存对有热点 key 的 prepared 点查有正向价值，但当前 benchmark key 分布较散，
命中率不高；`decodeColumns` 本身平均只有个位数微秒，因此 mixed 的剩余瓶颈仍在
`PRIMARY_FIND`、`RANGE_COUNT_FAST` 和 `ADD_ROW` 这些表/索引入口整体，而不是单独的列值解码。
下一阶段应优先降低 range count cursor 扫描成本，或进一步拆分 `PRIMARY_FIND` 内部的
`getVisible`、cache lookup 和 Row/ResultSet 对象创建耗时。

## 后续优化靶点

| 优先级 | 靶点 | 验证方式 |
| --- | --- | --- |
| P0 | 普通 SQL INSERT 自动命中 bulk 入口 | 已通过 ADB JDBC 兼容 Driver 包装参数化多值 `PreparedStatement` 和简单 literal 多值 `Statement`，将 `INSERT INTO ... VALUES ...` 路由到 `bulkInsertAppendRows`；后续仍需 h2db 原生表级 bulk hook 覆盖表达式、触发器和更完整语法 |
| P0 | 优化主键查找和点查对象边界 | 基于 `phaseStats` 对比 `PRIMARY_FIND`、`POINT_LOOKUP_FAST` 的 avg/max 耗时和对象分配 |
| P0 | 优化 batch 写入路径 | 支持一次 SQL 事务内批量写入时减少 per-row writeBatch、txn ref 扫描和 row-count 重复成本 |
| P1 | 拆分 primary find 内部阶段 | 区分 `getVisible`、decoded row cache、Row 对象创建和 H2 cursor/result 边界耗时 |
| P1 | range scan 避免 SQL COUNT 路径上的额外 materialization | 对比 `LdbStore` scan 与 SQL scan 的行迭代次数、对象创建数 |
| P1 | 细化多线程关键路径耗时 | 基于 `threads` benchmark 区分 commit、row-count、主键查找、range count、store 写入和锁等待 |

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

## 第七轮普通 JDBC insert 自动 bulk 结果

本轮新增 `net.xdob.vexra.adb.jdbc.AdbDriver` 作为 `jdbc:adb:*` 的轻量兼容
Driver。真实连接、SQL 解析和非命中语句仍委托给 h2db；当调用方通过
`DriverManager` 使用参数化多值 `PreparedStatement`：

```sql
INSERT INTO TEST(ID, NAME) VALUES (?, ?), (?, ?), ...
```

或简单 literal 多值 `Statement`：

```sql
INSERT INTO TEST(ID, NAME) VALUES (1, 'a'), (2, 'b'), ...
```

且目标表是 `AdbTable` 时，包装层会把参数或字面量转换成 H2 `Row`，调用
`AdbTable.bulkInsertAppendRows`，并在 `autoCommit=true` 时补齐 JDBC 自动提交边界。
不匹配的 SQL、非 ADB 表、参数不完整或表达式 literal 继续走 h2db 原路径。

验证结果：

| 模式 | workload | batch | diagnostics | throughput ops/s | p99 us | 结果文件 | 说明 |
| --- | --- | ---: | --- | ---: | ---: | --- | --- |
| `jdbc` | `insert` | 1000 | on | 43478.26 | 23 | `vexra-adb/build/adb-benchmark/jdbc_insert_driver_bulk_diag_r2.properties` | 诊断确认只记录 1 次 `ADB_TABLE_BULK_ADD_ROW ADB_BENCH` |
| `jdbc` | `insert` | 3000 | off | 76923.08 | 13 | `vexra-adb/build/adb-benchmark/jdbc_insert_driver_bulk_no_diag_r2.properties` | 普通 JDBC SQL 自动命中 bulk path，超过 3000 / 5000 ops/s 目标 |
| `jdbc` | `mixed` | 100 | on | 1779.36 | 2093 | `vexra-adb/build/adb-benchmark/jdbc_mixed_driver_bulk.properties` | mixed 回归；上一轮可比结果约 1697.79 ops/s |
| `jdbc` | `insert` | 3000 | off | 73170.73 | 13 | `vexra-adb/build/adb-benchmark/jdbc_insert_driver_bulk_literal_stage.properties` | literal Statement 支持加入后的 insert 回归 |
| `jdbc` | `mixed` | 100 | on | 1718.21 | 2027 | `vexra-adb/build/adb-benchmark/jdbc_mixed_driver_bulk_literal_stage.properties` | literal Statement 支持加入后的 mixed 回归 |

本轮新增集成测试 `preparedMultiValuesInsertUsesAdbDriverBulkPath`、
`statementLiteralMultiValuesInsertUsesAdbDriverBulkPath` 和
`unsupportedStatementInsertFallsBackToH2Path`，覆盖
`DriverManager + jdbc:adb:* + PreparedStatement/Statement` 的普通 SQL 写法，并断言可命中语句的诊断项为
`ADB_TABLE_BULK_ADD_ROW TEST`，不会退回逐行 `ADB_TABLE_ADD_ROW TEST`；表达式 literal 会回退 h2db。

JDBC insert 自动 bulk 复现命令：

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

剩余限制：当前自动 bulk 覆盖参数化 `PreparedStatement` 和简单 literal
`Statement` 的 `VALUES` 插入，包括单行和多行形态；但不覆盖 `INSERT ... SELECT`、`DEFAULT VALUES`、表达式/函数 literal、
`ON DUPLICATE KEY`、`RETURNING` 等语法。要做到完全透明，仍建议
h2db 在 `Insert` 执行层提供表级 bulk 回调，ADB 的 `bulkInsertAppendRows` 可继续作为落点。

## 第八轮 JDBC 主键点查快路径结果

本轮在 `jdbc:adb:*` 兼容 Driver 层增加参数化主键点查快路径，识别窄 SQL 形态：

```sql
SELECT col[, ...] FROM table WHERE pk = ?
```

命中条件包括：目标表必须是 `AdbTable`，`WHERE` 列必须是表主键列或 ROWID，投影列必须是简单列名。命中后不再进入 h2db
通用查询执行器和 `AdbPrimaryIndex.find`，而是直接通过当前 session 的 `TxnMap2` 读取可见 `RowValue`，并用
`RowCodec.decodeColumns` 只解码投影列。其他 SQL、非主键条件、表达式投影、未设置参数或非 ADB 表继续回退 h2db 原路径。

同时，`TxnManager` 的 committed row cache 默认仍会用 `VersionKey` 校验底层 committed version 存在，保护
restore 后读取不返回旧缓存；如需在纯本地压测中评估最短点查路径，可通过
`-Dvexra.adb.rowCache.trustCommitted=true` 显式跳过该校验。

验证结果：

| 模式 | workload | batch | diagnostics | throughput ops/s | p50 us | p95 us | p99 us | max us | 结果文件 |
| --- | --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| `jdbc` | `point_lookup` | 1 | on | 2664.30 | 325 | 688 | 1065 | 2274 | `vexra-adb/build/adb-benchmark/point_lookup_driver_safe_stage.properties` |
| `jdbc` | `mixed` | 100 | on | 1623.38 | 477 | 1403 | 2230 | 7069 | `vexra-adb/build/adb-benchmark/jdbc_mixed_driver_point_safe_stage.properties` |

本轮新增集成测试 `preparedPrimaryKeyLookupUsesAdbDriverFastPath`，覆盖 `DriverManager + jdbc:adb:* + PreparedStatement`
主键点查，断言结果正确，并通过 diagnostics 确认命中 `ADB_TABLE_POINT_LOOKUP_FAST TEST`，不再进入
`ADB_TABLE_PRIMARY_FIND TEST`。

结论：普通 JDBC 主键点查从早期约 228.80 ops/s、上一轮 SQL 路径约 770-780 ops/s，提升到约 2664.30 ops/s；
但 mixed 未继续提升，主要因为 mixed 中仍有 range/count 路径产生 900 次 `ADB_TABLE_PRIMARY_FIND ADB_BENCH`，
且事务提交与范围扫描仍在竞争总耗时。下一轮最有价值的优化应继续围绕 range/count 的 SQL 快路径或 mixed 中的事务提交成本。

点查复现命令：

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

mixed 回归命令：

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

## 第九轮 JDBC range/count 快路径结果

本轮在 `jdbc:adb:*` 兼容 Driver 层增加参数化主键范围 COUNT 快路径，识别窄 SQL 形态：

```sql
SELECT COUNT(*) FROM table WHERE pk BETWEEN ? AND ?
```

命中条件包括：目标表必须是 `AdbTable`，`WHERE` 列必须是表主键列或 ROWID，聚合必须是简单
`COUNT(*)`。命中后不再进入 h2db 通用查询执行器和聚合链路，而是直接复用当前 session 的
`TxnMap2.entryIterator` / `TableScanCursor` 在事务快照内计数，避免为 COUNT 构造 H2 `Row`
和聚合对象。非主键范围、二级索引范围、表达式、别名和其他 SQL 继续回退 h2db 原路径。

验证结果：

| 模式 | workload | batch | diagnostics | throughput ops/s | p50 us | p95 us | p99 us | max us | 结果文件 |
| --- | --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| `jdbc` | `range_scan` | 1 | on | 1388.89 | 668 | 1312 | 1750 | 7718 | `vexra-adb/build/adb-benchmark/range_count_fast_stage.properties` |
| `jdbc` | `mixed` | 100 | on | 1651.07 | 474 | 1177 | 2002 | 7973 | `vexra-adb/build/adb-benchmark/jdbc_mixed_range_count_fast_stage.properties` |

本轮新增集成测试 `preparedPrimaryKeyRangeCountUsesAdbDriverFastPath` 和
`preparedNonPrimaryRangeCountFallsBackToH2Path`，覆盖主键 BETWEEN COUNT 快路径和非主键范围回退边界。
diagnostics 可见纯 range 正式窗口只记录 `ADB_TABLE_RANGE_COUNT_FAST ADB_BENCH`；
mixed 中 `ADB_TABLE_RANGE_COUNT_FAST ADB_BENCH` 记录 600 次，说明 benchmark 的 range/count 部分已命中快路径。

结论：纯 `range_scan` 从上一轮 SQL 路径约 551.98 ops/s 提升到约 1388.89 ops/s，p99 从约
4613us 降到 1750us；`mixed` 从上一轮安全默认约 1623.38 ops/s 小幅提升到约 1651.07 ops/s，
p99 从约 2230us 降到 2002us。mixed 仍未大幅提升，说明后续瓶颈更可能在点查 committed cache 校验、
事务提交、以及混合负载中的剩余 H2 执行器边界。

range/count 复现命令：

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

mixed 回归命令：

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

## 第十轮 JDBC `SELECT *` 点查快路径结果

本轮把主键点查快路径扩展到常见的 `SELECT * FROM table WHERE pk = ?`
SQL 形态。上一轮点查快路径只接受 `SELECT NAME FROM ...` 这类显式列清单，
因此 ORM 或手写 SQL 中常见的 `SELECT *` 点查仍会回退到 h2db 通用查询执行器。
新实现会在解析到目标 `AdbTable` 后展开 `*` 为表的全部列，继续保留主键条件校验，
并直接把当前事务可见的 `RowValue` 解码成全部表列。

验证结果：

| 模式 | workload | batch | diagnostics | throughput ops/s | p50 us | p95 us | p99 us | max us | 结果文件 |
| --- | --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| `jdbc` | `point_lookup_all` | 1 | on | 1767.83 | 418 | 1160 | 1554 | 3146 | `vexra-adb/build/adb-benchmark/point_lookup_all_fast_stage.properties` |

正式统计窗口中 diagnostics 只记录 `ADB_TABLE_POINT_LOOKUP_FAST ADB_BENCH`，
确认该 `SELECT *` 主键点查形态不再进入 `ADB_TABLE_PRIMARY_FIND`。该结果低于只读取
单列的 `point_lookup` 是预期现象，因为它会返回并读取全部列；本轮价值在于覆盖更常见的
应用 SQL 形态，并移除 h2db 通用查询执行边界。

`SELECT *` 点查复现命令：

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

## 第十一轮 JDBC 全表 COUNT 快路径与 row-count 缓存结果

本轮新增窄 SQL 形态的快路径：

```sql
SELECT COUNT(*) FROM table
```

`PreparedStatement.executeQuery()` 和 `Statement.executeQuery(sql)` 现在可以直接从
ADB row-count 元数据叠加当前事务本地 row-count delta 得到结果。其他聚合形态、`WHERE`
条件、别名和表达式继续回退到 h2db 原执行路径。

第一版只绕过 h2db 聚合，但读取 committed row-count 基值时仍会扫描持久化 row-count
delta，实测约 761.61 ops/s、p99 2785us，说明瓶颈转移到了 row-count 元数据解析本身。
因此本轮同时加入保守的进程内 committed row-count 缓存：第一次读取仍从 META 加载，
提交成功后按已经落盘的 row-count delta 增量更新缓存，truncate/table epoch 更新会清理该表缓存。
进程重启或 restore 后缓存为空，会回到已有持久化扫描路径，不改变磁盘格式。

缓存后验证结果：

| 模式 | workload | batch | diagnostics | throughput ops/s | p50 us | p95 us | p99 us | max us | 结果文件 |
| --- | --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| `jdbc` | `table_count` | 1 | on | 2577.32 | 351 | 657 | 1115 | 2005 | `vexra-adb/build/adb-benchmark/table_count_cache_stage.properties` |

正式统计窗口中 diagnostics 记录 `ADB_TABLE_TABLE_COUNT_FAST ADB_BENCH`。新增集成测试还覆盖了
快路径能看到未提交本地 row-count delta，并在 rollback 后恢复到已提交计数。

全表 count 复现命令：

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

## 第十二轮 JDBC 单行 INSERT 快路径结果

本轮把 `jdbc:adb:*` 兼容 Driver 的普通 `INSERT INTO ... VALUES ...`
自动 bulk 路径从多 values 扩展到单行 values：

```sql
INSERT INTO TEST(ID, NAME) VALUES (?, ?)
INSERT INTO TEST(ID, NAME) VALUES (1, 'a')
```

命中条件仍保持保守：目标表必须是 `AdbTable`，列清单必须明确，PreparedStatement
必须全部由 `?` 参数组成，Statement literal 只接受简单数值、字符串、布尔和 `NULL`。
表达式、函数、子查询、`DEFAULT VALUES`、`ON DUPLICATE KEY` 和 `RETURNING`
继续回退 h2db 原路径。benchmark 的单行写入语句也从 `MERGE INTO` 改为普通
`INSERT INTO`，使 `insert` 和 `mixed` workload 可以真实覆盖普通 JDBC 单行写。

验证结果：

| 模式 | workload | batch | diagnostics | throughput ops/s | p50 us | p95 us | p99 us | max us | 结果文件 |
| --- | --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| `jdbc` | `insert` | 1 | on | 1044.93 | 933 | 1528 | 3087 | 11188 | `vexra-adb/build/adb-benchmark/jdbc_insert_single_bulk_stage.properties` |
| `jdbc` | `mixed` | 100 | on | 2024.29 | 390 | 1025 | 1920 | 6522 | `vexra-adb/build/adb-benchmark/jdbc_mixed_single_bulk_stage.properties` |

diagnostics 显示单行 insert 正式窗口记录 `ADB_TABLE_BULK_ADD_ROW ADB_BENCH`
2000 次，不再记录 `ADB_TABLE_ADD_ROW ADB_BENCH`；mixed 中写入部分记录
`ADB_TABLE_BULK_ADD_ROW ADB_BENCH` 200 次，点查和 range count 仍分别走已有快路径。
本轮新增集成测试覆盖 prepared 单行 insert 和 literal 单行 insert 的 bulk 命中，并保留表达式
literal 回退测试。

单行 insert 复现命令：

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

mixed 复现命令：

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

## 第十三轮 `PRIMARY_FIND` 对象边界诊断与解码路径结果

本轮把 H2 primary index 路径下的点查对象边界拆得更细，并减少 cache miss 时的临时 Row
拆装。旧路径在 `AdbPrimaryIndex.decodePointRow` cache miss 时先通过
`RowCodec.decode` 构造完整 H2 `Row`，再为 decoded row cache 把 `Row` 拆回 `Value[]`。
新路径改为：

1. `RowCodec.decodeRowValues` 直接把 payload 解码为 `Value[]`。
2. decoded row cache 保存 `Value[]`。
3. 只有返回 H2 cursor 前才通过 `Value[] -> DefaultRow` 构造 Row。

详细诊断新增阶段：

| phase | 含义 |
| --- | --- |
| `ADB_PRIMARY_FIND_ROW_DECODE` | payload 到 `Value[]` 的完整列值解码 |
| `ADB_PRIMARY_FIND_ROW_BUILD` | `Value[]` 到 H2 `DefaultRow` 的对象边界 |

同时新增 benchmark workload `primary_find`，使用普通 `Statement`
执行 `SELECT NAME FROM ADB_BENCH WHERE ID = <id>`，用于绕过 prepared point lookup
快路径并稳定触发 H2 `AdbPrimaryIndex.find`。

验证结果：

| 模式 | workload | diagnostics | throughput ops/s | p50 us | p95 us | p99 us | max us | 结果文件 |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| `jdbc` | `primary_find` | detailed on | 435.29 | 2174 | 3395 | 4350 | 8600 | `vexra-adb/build/adb-benchmark/primary_find_row_boundary_stage.properties` |

主要阶段摘要：

| phase | count | avg us | max us |
| --- | ---: | ---: | ---: |
| `ADB_TABLE_PRIMARY_FIND ADB_BENCH` | 3000 | 448 | 3000 |
| `ADB_PRIMARY_FIND_VISIBLE_ROW` | 3000 | 6 | 108 |
| `ADB_PRIMARY_FIND_ROW_BUILD` | 3000 | 0 | 88 |
| `ADB_PRIMARY_FIND_ROW_DECODE` | 2700 | 5 | 85 |
| `ADB_PRIMARY_FIND_ROW_CACHE_HIT` | 300 | 2 | 78 |
| `ADB_PRIMARY_FIND_ROW_CACHE_MISS` | 2700 | 8 | 241 |

结论：`PRIMARY_FIND` 内部可见性解析、payload 解码和 H2 Row 构造都已经拆分可见；
本轮还移除了 cache miss 时“先构造 Row 再拆数组”的中间对象。当前 `primary_find`
整体吞吐仍明显低于 prepared point lookup 快路径，说明剩余大头更可能在 H2 Statement
解析/执行器、row-count 调用和 ResultSet 外层，而不是单独的 row payload decode。

`primary_find` 复现命令：

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

## 第十四轮 range count 外层 ResultSet / prefix 固定成本结果

本轮继续收窄 `ADB_TABLE_RANGE_COUNT_FAST` 的外层固定成本：

1. `AdbPreparedRangeCountPlan` 缓存当前 `TabId` 对应的 `RowPrefix`，同一 table epoch
   内重复执行 prepared range count 时不再反复构造前缀。若 truncate / DDL 推进 epoch，
   `TabId` 改变后会自动重建 prefix。
2. `AdbSimpleResultSet` 新增单列 long 结果专用 handler，`COUNT(*)` 快路径不再为每次查询构造
   `Value[]` / `ValueBigint`；`findColumn("COUNT(*)")`、`getLong("COUNT(*)")`
   和 `getString(1)` 仍保持可用。

验证结果：

| 模式 | workload | diagnostics | throughput ops/s | p50 us | p95 us | p99 us | max us | 结果文件 |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| `jdbc` | `range_scan` | on | 1144.60 | 701 | 1959 | 2682 | 6749 | `vexra-adb/build/adb-benchmark/range_count_resultset_stage.properties` |
| `jdbc` | `mixed` | on | 877.45 | 1086 | 1827 | 3883 | 8169 | `vexra-adb/build/adb-benchmark/jdbc_mixed_range_resultset_stage.properties` |

主要阶段摘要：

| workload | phase | count | avg us | max us |
| --- | --- | ---: | ---: | ---: |
| `range_scan` | `ADB_RANGE_COUNT_VISIBLE_COUNT` | 3000 | 318 | 6018 |
| `range_scan` | `ADB_TABLE_RANGE_COUNT_FAST ADB_BENCH` | 3000 | 868 | 6000 |
| `mixed` | `ADB_RANGE_COUNT_VISIBLE_COUNT` | 600 | 412 | 6783 |
| `mixed` | `ADB_TABLE_RANGE_COUNT_FAST ADB_BENCH` | 600 | 1408 | 9000 |

结论：本轮减少了 range count 快路径中的小对象分配，但短跑结果没有超过第九轮
`range_count_fast_stage` 的 1388.89 ops/s，也低于上一轮单行 insert 改造后的 mixed 小窗口。
这说明当前 range count 的主要收益空间不在单个 `ResultSet` / `Value[]` 分配，而更可能在
可见行扫描成本、查询执行外层波动和 mixed 中 point lookup / commit 竞争。后续若继续优化
range count，更有价值的方向是 block-level / segment count，或把常见宽范围计数转成元数据级统计。

range count 复现命令：

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

mixed 复现命令：

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

## 第十五轮 benchmark allocation 指标结果

本轮在 benchmark 正式统计窗口中增加 JVM 线程分配字节指标，用于避免只靠延迟推测对象成本。
实现使用 `com.sun.management.ThreadMXBean`：

- 单线程 `jdbc`、`jdbc_bulk`、`txn` 和 `store` 模式记录当前线程正式窗口分配。
- 多线程 `jdbc` 模式在每个 worker 内记录正式窗口分配并汇总。
- 当前 JVM 不支持线程分配统计时输出 `allocation.supported=false`，不影响 benchmark 运行。

新增 properties：

| 字段 | 含义 |
| --- | --- |
| `allocation.supported` | 当前 JVM 是否支持线程分配字节统计 |
| `allocation.totalBytes` | 正式统计窗口内分配总字节数 |
| `allocation.bytesPerOperation` | 平均每个 operation 的分配字节数 |

首轮验证结果：

| 模式 | workload | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | 结果文件 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| `jdbc` | `point_lookup` | 2155.17 | 364 | 989 | 1439 | 10037 | `vexra-adb/build/adb-benchmark/point_lookup_allocation_stage.properties` |
| `jdbc` | `mixed` | 934.87 | 1031 | 1769 | 3479 | 275308 | `vexra-adb/build/adb-benchmark/jdbc_mixed_allocation_stage.properties` |

随后按同一口径补充 workload allocation 分解：

| 模式 | workload | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | 主要操作 | 结果文件 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- | --- |
| `jdbc` | `insert` | 1004.35 | 882 | 1974 | 3037 | 13399 | `ADB_TABLE_BULK_ADD_ROW` | `vexra-adb/build/adb-benchmark/jdbc_insert_allocation_stage.properties` |
| `jdbc` | `point_lookup` | 2155.17 | 364 | 989 | 1439 | 10037 | `ADB_TABLE_POINT_LOOKUP_FAST` | `vexra-adb/build/adb-benchmark/point_lookup_allocation_stage.properties` |
| `jdbc` | `primary_find` | 431.97 | 1882 | 4476 | 5536 | 51285 | `ADB_TABLE_PRIMARY_FIND` / `ADB_TABLE_ROW_COUNT` | `vexra-adb/build/adb-benchmark/primary_find_allocation_stage.properties` |
| `jdbc` | `table_count` | 1157.41 | 831 | 1517 | 1883 | 9193 | `ADB_TABLE_TABLE_COUNT_FAST` | `vexra-adb/build/adb-benchmark/table_count_allocation_stage.properties` |
| `jdbc` | `range_scan` | 1209.68 | 687 | 1550 | 2181 | 1245527 | `ADB_TABLE_RANGE_COUNT_FAST` | `vexra-adb/build/adb-benchmark/range_count_allocation_stage.properties` |
| `jdbc` | `mixed` | 934.87 | 1031 | 1769 | 3479 | 275308 | bulk add / point lookup / range count | `vexra-adb/build/adb-benchmark/jdbc_mixed_allocation_stage.properties` |

结论：allocation 指标已经能直接暴露对象成本。单独 prepared point lookup、table count
和单行 insert 都在约 `9KB-14KB/op`，`primary_find` 约 `51KB/op`，而 `range_scan`
达到约 `1.25MB/op`；mixed 约 `275KB/op` 与其 20% range count 占比相符。因此下一轮若以
allocation 为目标，最有价值的方向不是继续抠单行写或 prepared 点查，而是优化 range count
逐 row 可见性扫描的对象分配，或做 row-count segment / block-level count；`primary_find`
则仍适合继续绕过 H2 Statement / row-count 外层。

point lookup allocation 复现命令：

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

mixed allocation 复现命令：

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

workload allocation 分解复现时保持同一参数口径，将 `-PadbBenchmarkWorkload`
分别替换为 `insert`、`range_scan`、`table_count`、`primary_find`，并使用对应输出文件：

| workload | output |
| --- | --- |
| `insert` | `vexra-adb/build/adb-benchmark/jdbc_insert_allocation_stage.properties` |
| `range_scan` | `vexra-adb/build/adb-benchmark/range_count_allocation_stage.properties` |
| `table_count` | `vexra-adb/build/adb-benchmark/table_count_allocation_stage.properties` |
| `primary_find` | `vexra-adb/build/adb-benchmark/primary_find_allocation_stage.properties` |

## 第十六轮 range count raw-key 低分配扫描结果

本轮针对第十五轮暴露出的 `range_scan` 高分配热点，给
`TxnManager.countVisibleRows` 增加无本地写事务的 raw-key 快路径：

1. 当 `txn.getWriteSet().isEmpty()` 时，range count 不再为每个逻辑行构造
   `VersionKey`、`DataKey` 和 row prefix 字节数组，而是直接按 version-row key 的固定
   offset 解析 `rowId` 与 committed 标志。
2. 逻辑行分组使用 raw key 前 21 字节比较，仍复用 `RowValue.decodeMetadata` 判断
   `commitTs`、`deleted` 和 payload 是否存在。
3. 一旦事务存在本地 insert/delete，仍回退到原来的保守路径，继续保证同事务本地写、
   rollback 和覆盖 store 版本的可见性语义。
4. 新增 `ADB_RANGE_COUNT_VISIBLE_COUNT_RAW` phase，用于确认 prepared range count 是否命中
   raw-key 路径。

验证命令：

```powershell
.\gradlew.bat :vexra-adb:test --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest
```

本轮可复现结果：

| 模式 | workload | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | 结果文件 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| `jdbc` | `range_scan` | 1676.91 | 419 | 1445 | 1880 | 501959 | `vexra-adb/build/adb-benchmark/range_count_raw_stage.properties` |
| `jdbc` | `mixed` | 1417.77 | 473 | 1641 | 3282 | 111014 | `vexra-adb/build/adb-benchmark/jdbc_mixed_range_raw_stage.properties` |

与第十五轮 allocation 分解相比，`range_scan` 从 `1209.68 ops/s` 提升到
`1676.91 ops/s`，每 operation 分配从 `1245527 bytes/op` 降到
`501959 bytes/op`；`mixed` 从 `934.87 ops/s` 提升到 `1417.77 ops/s`，分配从
`275308 bytes/op` 降到 `111014 bytes/op`。这说明当前最重的 range count 成本主要来自逐行
key materialization，而不是 ldb 本身。下一步若继续做 range count，应转向 segment /
block-level count；若继续优化 mixed，优先级应回到 `POINT_LOOKUP_FAST`、
`PRIMARY_FIND` 和普通写入入口对象边界。

range count raw-key 复现命令：

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

mixed 复现命令：

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

## 第十七轮 prepared point lookup 单列 Value 快路径结果

本轮继续压缩 `SELECT col FROM table WHERE ID = ?` 这类 prepared 主键点查的对象边界：

1. `RowCodec.decodeColumn` 新增单列 payload 解码入口，单列投影不再先构造
   `Value[]`。
2. `AdbPreparedPointLookupPlan` 为单列投影维护 `rowId + commitTs -> Value`
   缓存；多列投影和 `SELECT *` 仍走原来的 `Value[]` 缓存。
3. `AdbSimpleResultSet.singleValue` 新增单值 ResultSet handler，已经拿到单个
   `Value` 时不再为了返回结果集额外构造数组。
4. 现有 `ADB_POINT_LOOKUP_DECODE_CACHE_HIT/MISS` 和
   `ADB_POINT_LOOKUP_RESULT_BUILD` phase 继续保留，用于和前几轮点查诊断对比。

验证命令：

```powershell
.\gradlew.bat :vexra-adb:test --tests net.xdob.vexra.adb.db.RowCodecTest
.\gradlew.bat :vexra-adb:test --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest
.\gradlew.bat :vexra-adb:test
```

本轮可复现结果：

| 模式 | workload | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | 结果文件 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| `jdbc` | `point_lookup` | 2685.77 | 318 | 706 | 1111 | 9904 | `vexra-adb/build/adb-benchmark/point_lookup_single_value_stage.properties` |
| `jdbc` | `mixed` | 1699.72 | 401 | 1450 | 2619 | 111358 | `vexra-adb/build/adb-benchmark/jdbc_mixed_point_single_value_stage.properties` |

与上一轮 `range_count_raw_stage` 后的可比结果相比，`mixed` 从 `1417.77 ops/s`
提升到 `1699.72 ops/s`，p99 从 `3282us` 降到 `2619us`；`ADB_TABLE_POINT_LOOKUP_FAST`
平均耗时从约 `536us` 降到约 `448us`。allocation 基本持平，说明本轮主要减少的是热路径小对象和方法边界成本，
不是大块 payload 或 key materialization。下一步若继续优化读路径，优先看 `PRIMARY_FIND`
的 H2 `SingleRowCursor` / `DefaultRow` 边界；若优化综合吞吐，则普通写入入口 `ADB_TABLE_BULK_ADD_ROW`
和 `ADB_TABLE_ADD_ROW` 仍值得继续压缩。

point lookup 复现命令：

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

mixed 复现命令：

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

## 第六轮普通 JDBC insert 微优化结果

本轮在 h2db 2.3.0 仍未提供 `Insert -> Table` 批量回调的前提下，只优化 ADB
自身普通 `Table.addRow` 热路径：

1. `TxnMap2` 缓存同一事务内同一表的 `TabId`，减少每行 `RowKey` 构造前的重复
   epoch 包装和对象分配。
2. `TxnMap2` 增加事务内 append 高水位。某个 row key 已经通过保守 append
   唯一性检查后，后续同一事务内更大的 rowId 可以跳过全局 rowId hint 查询和
   本地 write-set 查询；乱序、重复、rollback/savepoint 和 truncate 会回到保守路径。
3. `TxnManager` 在 commit 成功后按表聚合 rowId hint，只对每张表更新一次
   `ConcurrentHashMap + AtomicLong` 上界，避免大批量 insert 提交后逐行更新 hint。
4. 新增普通多 values insert 在显式事务内遇到重复主键后可 rollback 清空的集成测试，
   防止 append 高水位误吞同一语句内重复 key。

当前可复现结果：

| 模式 | workload | batch | diagnostics | throughput ops/s | p99 us | 结果文件 | 说明 |
| --- | --- | ---: | --- | ---: | ---: | --- | --- |
| `jdbc` | `insert` | 3000 | off | 2245.51 | 445 | `vexra-adb/build/adb-benchmark/jdbc_insert_no_diag_current.properties` | 本轮优化前、关闭诊断的当前代码基线 |
| `jdbc` | `insert` | 3000 | off | 2631.58 | 380 | `vexra-adb/build/adb-benchmark/jdbc_insert_commit_hint_batch_no_diag_r2.properties` | 本轮优化后、单独重跑结果 |
| `jdbc` | `mixed` | 100 | on | 1697.79 | 2195 | `vexra-adb/build/adb-benchmark/jdbc_mixed_append_highwater.properties` | mixed 回归；上一轮可比结果约 1538.46 ops/s |

结论：ADB 侧普通 `addRow` 微优化能改善真实 SQL insert 和 mixed，但还不能稳定达到
`>3000 ops/s`，更不能接近 `>5000 ops/s`。真正完成“普通
`INSERT INTO ... VALUES (...), (...)` 自动命中 bulk path”仍需要 h2db 在
`Insert` 层提供保留 trigger、constraint、generated column、delta table 和
`ON DUPLICATE KEY` 语义的表级 bulk insert SPI；ADB 当前保留的
`bulkInsertAppendRows` 已可作为该 SPI 的落点。

JDBC insert 关闭诊断复现命令：

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

## 第五轮 range scan / count 优化结果

本轮优化 `TableScanCursor` 的范围扫描可见性解析路径。旧实现已经在主扫描器上定位到当前
logical row，但仍会调用 `DefaultVisibleRowResolver` 为同一个 row 再打开一次 committed version
扫描；`SELECT COUNT(*) ... WHERE ID BETWEEN ? AND ?` 会为范围内每一行重复这笔开销。新实现直接
在当前 `VersionScanSource` 上解析同一 logical row 的可见版本，同时保留当前事务本地 write set
优先、`startTs` 快照可见性、deleted 过滤和 rowId 回填语义。

验证结果：

| 模式 | workload | batch | throughput ops/s | p50 us | p95 us | p99 us | max us | 结果文件 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `jdbc` | `range_scan` | 1 | 551.98 | 1470 | 3554 | 4613 | 8419 | `vexra-adb/build/adb-benchmark/range_scan_inline_visible.properties` |
| `jdbc` | `mixed` | 100 | 1538.46 | 453 | 1626 | 2686 | 7788 | `vexra-adb/build/adb-benchmark/jdbc_mixed_range_inline_visible.properties` |

与初始基线相比，`range_scan` 从约 72.80 ops/s 提升到约 551.98 ops/s；与上一轮 mixed
可比结果相比，`mixed` batch 100 从约 981.68 ops/s 提升到约 1538.46 ops/s。该结果说明
range/count 的主要瓶颈之一确实是每行重复打开可见性扫描器，而不是底层 ldb 本体。本轮完整执行
`.\gradlew.bat :vexra-adb:test --rerun-tasks` 通过，并新增范围 COUNT 在同一事务内看到本地 delete、
rollback 后恢复的集成测试。

range 复现命令：

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

mixed 复现命令：

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

## 第四轮点查优化结果

本轮围绕 JDBC 主键点查和 mixed workload 中的点查比例做了两项低风险快路径：

1. `TxnManager` 在本地提交成功后缓存 row key 对应的最新 committed `RowValue`，点查事务在
   `commitTs <= startTs` 时可以跳过 committed version 前缀扫描。为避免 checkpoint/restore 后读到
   旧内存缓存，缓存命中前会用精确 `VersionKey` 做一次底层存在性校验；若物理版本不存在则失效并回退扫描。
2. `AdbPrimaryIndex` 对主键点查增加有上限的 decoded row cache，使用 `RowKey + commitTs` 校验，避免重复
   payload decode；更新后 commitTs 改变会自动失效，删除或表清理会移除缓存。

验证结果：

| 模式 | workload | batch | throughput ops/s | p50 us | p95 us | p99 us | max us | 结果文件 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `jdbc` | `point_lookup` | 1 | 781.45 | 1020 | 2661 | 3748 | 8216 | `vexra-adb/build/adb-benchmark/point_lookup_committed_cache.properties` |
| `jdbc` | `mixed` | 100 | 981.68 | 612 | 3580 | 5215 | 8341 | `vexra-adb/build/adb-benchmark/jdbc_mixed_point_cache.properties` |

与上一轮可比结果相比，单独 `point_lookup` 从约 770 ops/s 小幅提升到约 781 ops/s，说明主键点查剩余大头仍在
H2 executor / JDBC `ResultSet` / Row 对象边界；`mixed` batch 100 从约 500 ops/s 提升到约 982 ops/s，
说明点查路径的可见版本扫描和 decode 开销在混合负载中确实会放大。本轮完整执行
`.\gradlew.bat :vexra-adb:test --rerun-tasks` 通过，并额外覆盖 committed cache 在 update、delete 和
backup/restore 边界下不返回旧值。

点查复现命令：

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

mixed 复现命令：

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

## 第五轮：range count 可见行计数优化

本轮把 `SELECT COUNT(*) FROM table WHERE pk BETWEEN ? AND ?` 的 JDBC fast path
从通用 `TableScanCursor` 迁移到 count-only 扫描：

1. `RowValue.decodeMetadata` 只解码 `txnId`、`commitTs`、`deleted` 和 payload 长度，不再为每个可见版本复制完整 payload。
2. `TxnManager.countVisibleRows` 复用原有 MVCC 可见性和 range read 路由，扫描 store 中已有逻辑行时优先应用当前事务本地 write-set。
3. 扫描结束后补计当前事务中尚未落到 store 的本地 row 写入，修正 prepared range count 对同事务新增行的可见性。
4. 新增 `ADB_RANGE_COUNT_VISIBLE_COUNT` phase，用于把 count-only 内部扫描耗时从 `ADB_TABLE_RANGE_COUNT_FAST` 入口耗时中拆出来。

本轮验证命令 `.\gradlew.bat :vexra-adb:test --rerun-tasks` 已通过；新增覆盖包括 prepared range count 对同事务 insert、delete 和 rollback 的可见性。

可复现结果：

| 模式 | workload | threads | operations | throughput ops/s | p50 us | p95 us | p99 us | 结果文件 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `jdbc` | `range_scan` | 1 | 3000 | 1149.87 | 740 | 1788 | 2547 | `vexra-adb/build/adb-benchmark/range_count_visible_count_stage.properties` |
| `jdbc` | `mixed` | 8 | 3000 | 1148.11 | 2175 | 10406 | 15640 | `vexra-adb/build/adb-benchmark/jdbc_mixed_visible_count_threads_8.properties` |

诊断结论：

- 纯 range count 中 `ADB_RANGE_COUNT_VISIBLE_COUNT` 平均约 296 us，而 `ADB_TABLE_RANGE_COUNT_FAST`
  平均约 859 us，说明 count-only 扫描已经把内部 payload 解码成本压低，但 JDBC/table-engine 入口仍有明显固定开销。
- mixed 8 线程中 `ADB_RANGE_COUNT_VISIBLE_COUNT` 平均约 671 us，`ADB_TABLE_RANGE_COUNT_FAST`
  平均约 2776 us；整体吞吐没有改善到上一轮 mixed 结果之上，说明当前 mixed 主瓶颈不在 range count 的 payload 解码。
- 同一 mixed 报告中 `ADB_ROW_COUNT_CACHE_MISS` / `ADB_ROW_COUNT_BASE_SCAN` 首次扫描约 70 ms，
  `ADB_TABLE_POINT_LOOKUP_FAST` 总耗时最高，`ADB_TABLE_PRIMARY_FIND` 和 `ADB_TABLE_ADD_ROW` 仍是高延迟入口。
  下一阶段更有价值的优化应转向 row-count 基线预热/持久化、point lookup/primary find 对象边界，以及写入入口的批量化。

range count 复现命令：

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

mixed 8 线程复现命令：

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

## 第十八轮：primary find cost 诊断与未采纳实验

本轮继续拆分 `PRIMARY_FIND` 的外层耗时，保留了一个 detailed-only 诊断：

- `ADB_PRIMARY_FIND_COST`：统计 H2 planner 调用 primary index `getCost` 时的耗时。
- 该 phase 只在 `vexra.adb.sql.diagnostic.detail=true` 时记录，默认 benchmark
  和线上热路径不增加额外 `System.nanoTime()` 调用。

同时验证了两个实验性方案，但没有合入为默认行为：

| 实验 | workload | throughput ops/s | p50 us | p95 us | p99 us | bytes/op | 结论 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 轻量 `Row` 视图替代 `DefaultRow` | `primary_find` | 302.85 | 3342 | 5512 | 6791 | 51223 | 相比上一轮 `431.97 ops/s` 明显回退，未采纳 |
| `getCost` 使用估算行数，跳过精确 `getRowCount` | `primary_find` | 524.02 | 1942 | 3191 | 4778 | 33770 | 单项 primary find 改善明显 |
| 同一估算行数方案 | `mixed` | 1135.93 | 713 | 2199 | 3350 | 111425 | mixed 相比上一轮 `1699.72 ops/s` 明显回退，未采纳 |

结论：

- `getCost -> getRowCount` 是 primary find 单项 workload 的真实成本来源之一；
  估算行数能让 `ADB_TABLE_ROW_COUNT` 从该路径消失，并降低对象分配。
- 该方案会影响 H2 optimizer 的索引选择或执行计划稳定性，在 mixed workload 中造成明显吞吐回退；
  因此不能直接把 primary index cost 改成固定估算值。
- 下一步若继续优化 primary find，应基于 `ADB_PRIMARY_FIND_COST` 做更窄的策略：
  例如只在安全的主键等值查询计划中跳过精确 row-count，或者把 row-count 基线改成可预热、
  可复用且不改变 optimizer 计划语义的缓存。

primary find cost 诊断复现命令：

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

## 第十九轮：row-count cache 打开预热

本轮推进 row-count 缓存深化：`AdbTable` 构造完成后默认调用
`TxnManager.prewarmRowCountCache(TabId)`，把 row-count base/delta meta 扫描提前到
数据库打开或表对象恢复阶段。该行为只填充进程内缓存，不修改持久化数据；如果启动阶段更关注
打开耗时，可以通过 `-Dvexra.adb.rowCount.prewarm=false` 关闭。

新增诊断 phase：

- `ADB_ROW_COUNT_PREWARM`：表打开阶段完成一次 row-count 缓存预热。
- `ADB_ROW_COUNT_PREWARM_HIT`：预热发现缓存已存在。

验证覆盖：

- `rowCountCachePrewarmsAfterReopen`：reopen 后第一次 `COUNT(*)` 命中
  `ADB_ROW_COUNT_CACHE_HIT`，不再出现 `ADB_ROW_COUNT_CACHE_MISS`。
- `rowCountCachePrewarmCanBeDisabled`：关闭 `vexra.adb.rowCount.prewarm` 后回到懒加载，
  第一次 `COUNT(*)` 仍记录一次 cache miss。
- `concurrentTableCountLoadsBaseRowCountOnce` 已调整为验证并发 count 共享预热缓存。

可复现结果：

| workload | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | 关键诊断 | 结果文件 |
| --- | ---: | ---: | ---: | ---: | ---: | --- | --- |
| `table_count` | 1318.68 | 731 | 1297 | 1844 | 9094 | 正式窗口 3000 次 `ADB_ROW_COUNT_CACHE_HIT`，无 miss/base scan | `vexra-adb/build/adb-benchmark/table_count_prewarm_stage.properties` |
| `primary_find` | 474.61 | 1989 | 3126 | 4087 | 51052 | 正式窗口 6000 次 `ADB_ROW_COUNT_CACHE_HIT`，无 miss/base scan | `vexra-adb/build/adb-benchmark/primary_find_prewarm_stage.properties` |
| `mixed` 8 线程 | 1051.16 | 2482 | 8499 | 11684 | 684348 | worker 连接后记录 1 次 prewarm/base scan，benchmark 并发口径仍包含连接/预热成本 | `vexra-adb/build/adb-benchmark/jdbc_mixed_prewarm_stage.properties` |

结论：

- 单线程 `table_count` 从第十五轮 allocation 基线的 `1157.41 ops/s` 提升到
  `1318.68 ops/s`；`primary_find` 从 `431.97 ops/s` 提升到 `474.61 ops/s`。
- 这次没有改变 H2 optimizer 的 cost 语义，因此比“固定估算行数”方案更稳；正式窗口也确认
  row-count 读取从 cold miss/base scan 变成 cache hit。
- 当前 mixed benchmark 的并发计时从 worker 启动后开始，包含每个 worker 的连接和 warmup；
  因此 mixed 这轮只作为副作用观察，不作为吞吐提升证据。后续 benchmark 应拆出
  `connection/open/prewarm` 与正式操作窗口，避免 allocation 和 throughput 被连接阶段污染。

table count 复现命令：

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

primary find 复现命令：

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

## 第二十轮：并发 benchmark 正式窗口拆分

本轮修正 `jdbc` 并发 benchmark 的统计口径。旧实现中，主线程在 worker 启动后立即开始计时，
worker 还需要打开 JDBC 连接、构造 `BenchmarkStatements`、执行 warmup，以及触发表打开阶段的
row-count prewarm；这些成本会污染 mixed 的 throughput、allocation 和 SQL phase。

新口径：

1. worker 先完成连接打开、statement 构造和 warmup。
2. 所有 worker ready 后，主线程调用 `AdbSqlDiagnosticsRegistry.resetAll()`。
3. 主线程打开正式统计闸门，worker 才开始 counted operations 和 allocation 采样。
4. 输出 `concurrency.measuredWindow=operationsOnly`，标识正式窗口不再包含连接/warmup/prewarm。

测试覆盖：

- `shouldRunConcurrentMixedBenchmarkAgainstLdbUrl` 验证输出
  `concurrency.measuredWindow=operationsOnly`。
- 同一测试断言正式窗口 diagnostics 不包含 `ADB_ROW_COUNT_PREWARM`。

修正口径后的 mixed 8 线程结果：

| workload | threads | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | 结果文件 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `mixed` | 8 | 2463.05 | 2370 | 8266 | 11042 | 732973 | `vexra-adb/build/adb-benchmark/jdbc_mixed_measured_window_stage.properties` |

正式窗口 phase 摘要：

| phase | count | avg us | max us |
| --- | ---: | ---: | ---: |
| `ADB_TABLE_POINT_LOOKUP_FAST ADB_BENCH` | 2100 | 2432 | 11000 |
| `ADB_TABLE_BULK_ADD_ROW ADB_BENCH` | 300 | 2876 | 11000 |
| `ADB_TABLE_RANGE_COUNT_FAST ADB_BENCH` | 600 | 2545 | 10000 |
| `ADB_RANGE_COUNT_VISIBLE_COUNT` | 600 | 355 | 2960 |
| `ADB_POINT_LOOKUP_DECODE_CACHE_MISS` | 2072 | 7 | 112 |

结论：

- mixed 吞吐口径从上一轮受连接/prewarm 污染的 `1051.16 ops/s` 修正到
  `2463.05 ops/s`，更接近真实 counted operations。
- 正式窗口不再包含 row-count prewarm phase，后续可以用 mixed 结果继续判断
  point lookup、bulk add 和 range count 的真实入口成本。
- 当前最大剩余热点仍是 table-engine/JDBC 入口层：`POINT_LOOKUP_FAST`、`BULK_ADD_ROW`
  和 `RANGE_COUNT_FAST` 平均都在 2.4ms-2.9ms；内部 decode/count 阶段已经不是最大头。

mixed 复现命令：

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

## 第二十一轮：JFR benchmark 诊断入口与 direct cache 负实验

本轮尝试用 rowId 直接映射缓存替换 prepared point lookup 中的
`ConcurrentHashMap<Long, ...>` decoded-column cache，以减少 `Long` 装箱和 CHM 访问成本。
实测结果不够稳定，未保留该代码：

| 实验 | workload | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | 结论 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| direct cache | `point_lookup` | 2074.69 | 387 | 709 | 1316 | 10015 | 低于上一轮单值快路径历史结果，未保留 |
| direct cache | `mixed` 8 线程 | 2479.34 | 2304 | 7852 | 11423 | 732992 | 与第 20 轮 `2463.05 ops/s` 接近，p99 更差，未保留 |

为了继续定位 `POINT_LOOKUP_FAST`、`BULK_ADD_ROW`、`RANGE_COUNT_FAST`
的外层对象和调用边界，本轮新增 JFR 诊断入口：

1. `:vexra-adb:adbBenchmark` 支持 `-PadbBenchmarkJvmArgs=...`，可向 benchmark
   JVM 透传 `-XX:StartFlightRecording` 等参数。
2. 新增 `scripts/adb-benchmark-jfr.ps1`，默认生成 `.jfr` 和 `.properties`
   到 `vexra-adb/build/adb-benchmark/jfr`。
3. 当前本地 JDK 8 需要 `-XX:+UnlockCommercialFeatures`，脚本已默认附加。

smoke test：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\adb-benchmark-jfr.ps1 `
  -Workload point_lookup `
  -Rows 20 `
  -WarmupOperations 2 `
  -Operations 5 `
  -Threads 1 `
  -OutputDir vexra-adb/build/adb-benchmark/jfr-smoke
```

smoke test 结果：

| 文件 | 结果 |
| --- | --- |
| `vexra-adb/build/adb-benchmark/jfr-smoke/adb-point_lookup-20260622-110910.jfr` | 已生成，216528 bytes |
| `vexra-adb/build/adb-benchmark/jfr-smoke/adb-point_lookup-20260622-110910.properties` | `passed=true`，`point_lookup` 5 operations |

后续分析建议：

- 用 JDK Mission Control 打开 `.jfr`，优先查看 allocation hot spots 和方法采样。
- 对比 `point_lookup` 与 `mixed` 的 `java.lang.reflect.Proxy`、`AdbSimpleResultSet`
  handler、`TxnMap2.getVisible`、`RowValue.decodeValue`、`PreparedStatement` proxy 调用栈。
- 若 JFR 证明 Proxy/handler 分配是大头，再考虑实现专用 `ResultSet` 类；否则继续压
  `TxnMap2.getVisible` 和 table-engine/JDBC 外层入口。

## 第二十二轮：mixed 8 线程 JFR 采集与 bulk 写入口低分配整理

本轮先按性能优化计划跑完整 `mixed` 8 线程 JFR，不再基于猜测判断 Proxy / ResultSet
是否是对象分配大头：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\adb-benchmark-jfr.ps1 `
  -Workload mixed `
  -Rows 5000 `
  -WarmupOperations 300 `
  -Operations 3000 `
  -Threads 8 `
  -OutputDir vexra-adb/build/adb-benchmark/jfr-mixed-8
```

采集结果：

| 文件 | 结果 |
| --- | --- |
| `vexra-adb/build/adb-benchmark/jfr-mixed-8/adb-mixed-20260622-112110.jfr` | 已生成，947880 bytes |
| `vexra-adb/build/adb-benchmark/jfr-mixed-8/adb-mixed-20260622-112110.properties` | `passed=true`，`mixed` 3000 operations |

本地 JDK 8 只有 `jcmd`，没有 `jfr.exe` / JDK Mission Control / JFR parser
jar，因此本轮无法在本机离线打印 allocation hot spots。为保证后续可以复现分析，
新增 `scripts/adb-jfr-hotspots.ps1`：在具备 JDK 11+ `jfr` CLI 的环境上，对指定
`.jfr` 导出：

1. `summary.txt`
2. `allocation-events.txt`
3. `execution-samples.txt`
4. `adb-focus.txt`，聚合 `java.lang.reflect.Proxy`、`AdbSimpleResultSet`、
   `AdbPreparedStatementProxy`、`TxnMap2.getVisible`、`DefaultVisibleRowResolver`、
   `RowValue.decodeValue`、`RowCodec` 和 commit/write batch 相关关键词。

使用方式：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\adb-jfr-hotspots.ps1 `
  -JfrFile vexra-adb/build/adb-benchmark/jfr-mixed-8/adb-mixed-20260622-112110.jfr
```

JFR 采集同轮的 `mixed` 8 线程结果：

| workload | threads | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | 结果文件 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `mixed` | 8 | 2401.92 | 2268 | 7832 | 11594 | 683419 | `vexra-adb/build/adb-benchmark/jfr-mixed-8/adb-mixed-20260622-112110.properties` |

由于 JFR 尚未能在本机解析出 Proxy / ResultSet allocation 大头，本轮没有贸然实现专用
`ResultSet`，而是先推进已经由 SQL diagnostics 证明仍较高的 `BULK_ADD_ROW` 入口整理：

1. `bulkInsertAppendRows` 去掉 `BulkRowWrite` 中间列表，不再先构造整批写入封装对象再二次遍历。
2. 每批只解析一次 `txnId` 和 `TabId`，避免每行重复读取事务 id 和表 epoch 缓存。
3. `HashSet` 按批大小预估容量，减少同批主键去重集合扩容。
4. 仍保留 savepoint 保护：任何主键冲突、唯一索引冲突或二级索引写入失败都会回滚整批。

新增测试覆盖同批重复主键时已经写入的前置行必须被回滚：

```powershell
.\gradlew.bat :vexra-adb:test --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest --rerun-tasks
```

验证结果：通过。

顺序 benchmark 结果：

| workload | threads | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | 结果文件 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `insert` | 1 | 819.00 | 972 | 2604 | 3600 | 37504 | `vexra-adb/build/adb-benchmark/insert_bulk_direct_write_stage_r3.properties` |
| `mixed` | 8 | 2479.34 | 2242 | 8142 | 11505 | 733347 | `vexra-adb/build/adb-benchmark/jdbc_mixed_bulk_direct_write_stage_r3.properties` |

结论：

- `mixed` 8 线程与第 20 轮正式窗口结果接近，说明本轮 bulk 写入口整理没有破坏综合路径。
- `insert` p50/p95/p99 明显好于 r2 顺序复跑，说明批写入口减少中间对象和重复查表有正向效果，但该结果仍需多轮长跑确认。
- `mixed` 的 `allocation.bytesPerOperation` 仍约 `733KB/op`，说明最大分配源不在被移除的
  `BulkRowWrite` 小对象上；后续仍需依赖 JFR CLI / JMC 输出确认 Proxy、ResultSet、
  visible row、decode 和 commit/write batch 的真实占比。
- 在没有 JFR 证据证明 Proxy / ResultSet 是大头前，暂不实现专用 `ResultSet`；下一步更有价值的是在
  JFR 可解析环境导出 `adb-focus.txt`，或继续拆 `TxnMap2.getVisible` / write batch
  内部阶段。

## 第二十三轮：visible row 内部分段诊断

本轮继续推进 `TxnMap2.getVisible` / visible row 解析路径，不直接猜测优化点，而是先把
prepared point lookup 和 primary find 都会经过的 `TxnManager.getVisible` 拆成 detail-only
phase。默认模式只读取 `TxnManager` 实例上缓存的 detail 开关，不做纳秒计时和 phase 记录。

新增分段：

| phase | 含义 |
| --- | --- |
| `ADB_VISIBLE_LOCAL_WRITE_CHECK` | 检查当前事务本地 write-set |
| `ADB_VISIBLE_LOCAL_WRITE_HIT` / `ADB_VISIBLE_LOCAL_WRITE_MISS` | 本地写命中/未命中 |
| `ADB_VISIBLE_ROUTE_POINT_READ` | region point read 路由边界 |
| `ADB_VISIBLE_COMMITTED_CACHE_HIT` / `ADB_VISIBLE_COMMITTED_CACHE_MISS` | committed row cache 命中/未命中 |
| `ADB_VISIBLE_COMMITTED_CACHE_VALIDATE` | 不信任 cache 时校验底层 committed version |
| `ADB_VISIBLE_STORE_SEEK` | 版本扫描 cursor seek 到逻辑行前缀 |
| `ADB_VISIBLE_VERSION_KEY_DECODE` | 解析 `VersionKey` |
| `ADB_VISIBLE_INTENT_SKIP` | 跳过未提交 intent 版本 |
| `ADB_VISIBLE_ROW_VALUE_DECODE` | `RowValue.decodeValue(...)` |
| `ADB_VISIBLE_STORE_ADVANCE` | scan advance |
| `ADB_VISIBLE_COMMITTED_STORE_SCAN` | committed store scan 总耗时 |
| `ADB_VISIBLE_READ_SET_RECORD` | 记录事务 read-set 版本 |

新增测试 `preparedPointLookupRecordsVisibleRowDiagnosticBreakdown` 覆盖：

1. 关闭并重开数据库后读取已提交行，触发 committed cache miss + store scan + row decode。
2. 同一事务内插入未提交行后点查，触发 local write hit。
3. 断言上述 visible row 分段 phase 进入 SQL diagnostics。

验证命令：

```powershell
.\gradlew.bat :vexra-adb:test --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest --rerun-tasks
```

验证结果：通过。

detail mixed 8 线程复现命令：

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkDetailedDiagnostics=true -PadbBenchmarkWorkload=mixed -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=8 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_visible_breakdown_detail_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-mixed-visible-breakdown-detail-stage/adb-benchmark
```

detail mixed 8 线程结果：

| workload | threads | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | 结果文件 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `mixed` detail | 8 | 2454.99 | 2386 | 8439 | 11670 | 733451 | `vexra-adb/build/adb-benchmark/jdbc_mixed_visible_breakdown_detail_stage.properties` |

visible row 关键分段：

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

默认 mixed 8 线程复跑：

| workload | threads | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | 结果文件 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `mixed` | 8 | 2400.00 | 2446 | 8525 | 11921 | 683624 | `vexra-adb/build/adb-benchmark/jdbc_mixed_visible_breakdown_default_stage.properties` |

结论：

- 在 detail mixed 中，visible row 的主要可见成本是 store scan / seek：`ADB_VISIBLE_COMMITTED_STORE_SCAN`
  平均约 `296us`，其中 `ADB_VISIBLE_STORE_SEEK` 平均约 `278us`。
- `ADB_VISIBLE_ROW_VALUE_DECODE` 平均接近 `0us`，说明当前 point lookup 的 payload decode 不是最大头。
- committed cache 在该 mixed 场景中全部表现为 miss，下一步更值得评估的是 committed row cache 的可用性、
  cursor/seek 复用，或针对只读点查的更直接 store get 路径。
- 默认 mixed 8 线程没有出现明显语义或性能回退；后续若继续优化第 3 项，应优先减少 store seek/scan 边界，
  而不是继续压 `RowValue.decodeValue`。

## 第二十四轮：visible committed cache 读后回填

第二十三轮显示 `ADB_VISIBLE_COMMITTED_STORE_SCAN` / `ADB_VISIBLE_STORE_SEEK`
是 visible row 的主要成本，并且 mixed 场景中 committed cache 全部 miss。检查后确认：

1. `RowKey` 继承 `Key` 的 byte-array `equals/hashCode`，cache key 等值语义正常。
2. `committedRowCache` 之前只在本进程 commit 后由 `refreshCommittedRowCache(...)` 刷新。
3. benchmark 预置数据和数据库重开后的历史数据，第一次从 store scan 读出后不会回填 cache，因此后续相同 row
   仍会继续 seek/scan。

本轮改动：

1. `TxnManager.getVisibleCommitted(...)` 从 store 解析出 committed 可见行后，回填 `committedRowCache`。
2. detail-only 的 `getVisibleCommittedDetailed(...)` 在 store scan 命中 committed 可见行后同样回填 cache。
3. deleted/null/非 row key 不缓存；缓存值复制 `rowKey`，避免共享可变 `RowValue` 状态。
4. 既有 commit/update/delete 后的 `refreshCommittedRowCache(...)` 仍负责覆盖或移除同 key cache entry。
5. 默认仍保留 `TRUST_COMMITTED_ROW_CACHE=false` 下的 committed version 存在性校验，避免 restore 后返回已不存在的旧 cache。

测试增强：

- `preparedPointLookupRecordsVisibleRowDiagnosticBreakdown` 现在先读同一 committed row 两次，断言第一次触发
  store scan，第二次触发 `ADB_VISIBLE_COMMITTED_CACHE_HIT` 和
  `ADB_VISIBLE_COMMITTED_CACHE_VALIDATE`。
- 同一测试继续覆盖当前事务本地写命中 `ADB_VISIBLE_LOCAL_WRITE_HIT`。

验证命令：

```powershell
.\gradlew.bat :vexra-adb:test --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest --rerun-tasks
```

验证结果：通过。

detail mixed 8 线程结果：

| workload | threads | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | 结果文件 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `mixed` detail | 8 | 2568.49 | 2327 | 7969 | 10319 | 711405 | `vexra-adb/build/adb-benchmark/jdbc_mixed_visible_cache_fill_detail_stage.properties` |

visible row 关键分段对比：

| phase | 第二十三轮 count / avg us | 第二十四轮 count / avg us | 说明 |
| --- | ---: | ---: | --- |
| `ADB_VISIBLE_COMMITTED_STORE_SCAN` | 2359 / 296 | 2139 / 276 | 读后回填后减少 220 次 store scan |
| `ADB_VISIBLE_STORE_SEEK` | 2359 / 278 | 2139 / 258 | seek 次数和均值均下降 |
| `ADB_VISIBLE_COMMITTED_CACHE_HIT` | 0 / - | 220 / 102 | 新增 cache 命中；默认仍含 version 校验 |
| `ADB_POINT_LOOKUP_VISIBLE_ROW` | 2100 / 240 | 2100 / 215 | 点查 visible row 均值下降 |

默认 mixed 8 线程复跑：

| workload | threads | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | 结果文件 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `mixed` | 8 | 2497.92 | 2379 | 8240 | 10566 | 663277 | `vexra-adb/build/adb-benchmark/jdbc_mixed_visible_cache_fill_default_stage.properties` |

point lookup 单项复跑：

| workload | threads | throughput ops/s | p99 us | 结果文件 |
| --- | ---: | ---: | ---: | --- |
| `point_lookup` | 1 | 1969.80 | 1673 | `vexra-adb/build/adb-benchmark/point_lookup_visible_cache_fill_stage.properties` |

结论：

- committed cache 读后回填对 mixed 有正向信号：detail mixed 从 `2454.99 ops/s` 到
  `2568.49 ops/s`，默认 mixed 从 `2400.00 ops/s` 到 `2497.92 ops/s`。
- 分段数据证明 store seek/scan 次数下降，并出现 220 次 committed cache hit。
- point_lookup 单项本轮没有超过历史最好值，因此不把该结果作为 headline；它需要结合随机访问模式、
  cache 校验成本和多轮复跑再判断。
- 下一步若继续第 3 项，最值得评估的是降低 cache hit 校验成本或提供受控的 trust 模式压测；
  若转向第 5 项，则可把类似“读后回填/专用 ResultSet”的思路用于 range count 外层入口。

## 第二十五轮：trusted committed cache 受控对照

第二十四轮读后回填让 committed cache 出现命中，但默认 `TRUST_COMMITTED_ROW_CACHE=false`
仍会在每次 cache hit 时通过底层 committed version 校验保护 restore 场景。为了量化“校验成本上限”，
本轮仅做受控压测，不改变默认安全策略。

detail mixed 8 线程 trusted 复现命令：

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkDetailedDiagnostics=true -PadbBenchmarkJvmArgs=-Dvexra.adb.rowCache.trustCommitted=true -PadbBenchmarkWorkload=mixed -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=8 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_trusted_visible_cache_detail_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-mixed-trusted-visible-cache-detail-stage/adb-benchmark
```

默认诊断关闭的 trusted mixed 8 线程复现命令：

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkJvmArgs=-Dvexra.adb.rowCache.trustCommitted=true -PadbBenchmarkWorkload=mixed -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=8 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_trusted_visible_cache_default_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-mixed-trusted-visible-cache-default-stage/adb-benchmark
```

结果对比：

| workload | mode | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | 结果文件 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| `mixed` | default safe cache | 2497.92 | 2379 | 8240 | 10566 | 663277 | `vexra-adb/build/adb-benchmark/jdbc_mixed_visible_cache_fill_default_stage.properties` |
| `mixed` | trusted cache | 2700.27 | 2185 | 7807 | 9920 | 663214 | `vexra-adb/build/adb-benchmark/jdbc_mixed_trusted_visible_cache_default_stage.properties` |
| `mixed` detail | default safe cache | 2568.49 | 2327 | 7969 | 10319 | 711405 | `vexra-adb/build/adb-benchmark/jdbc_mixed_visible_cache_fill_detail_stage.properties` |
| `mixed` detail | trusted cache | 2645.50 | 2268 | 7573 | 10503 | 663144 | `vexra-adb/build/adb-benchmark/jdbc_mixed_trusted_visible_cache_detail_stage.properties` |

trusted detail 关键 phase：

| phase | count | avg us | max us |
| --- | ---: | ---: | ---: |
| `ADB_VISIBLE_COMMITTED_CACHE_MISS` | 2139 | 0 | 46 |
| `ADB_VISIBLE_STORE_SEEK` | 2139 | 237 | 6936 |
| `ADB_VISIBLE_COMMITTED_STORE_SCAN` | 2139 | 256 | 6957 |
| `ADB_VISIBLE_COMMITTED_CACHE_HIT` | 220 | 1 | 22 |
| `ADB_POINT_LOOKUP_VISIBLE_ROW` | 2100 | 184 | 6965 |

结论：

- `-Dvexra.adb.rowCache.trustCommitted=true` 的收益明显：默认 mixed 从 `2497.92 ops/s`
  到 `2700.27 ops/s`，p99 从 `10566us` 到 `9920us`。
- detail phase 显示 trusted cache hit 平均约 `1us`，而第二十四轮默认安全 cache hit 平均约
  `102us`，差异主要来自底层 committed version 校验。
- 该模式不适合作为默认值：在同一进程内执行 restore / checkpoint 回滚等操作时，跳过校验可能返回旧的
  in-memory cache。默认策略继续保留安全校验。
- 下一步若要把这部分收益安全默认化，需要在 `DbStore.restore(...)` / backup-restore runtime
  与 `TxnManager` 之间建立 cache invalidation / generation 机制；否则只能作为纯本地、无 restore 干扰的压测开关。

## 第二十六轮：trusted committed cache 的 restore 失效边界

第二十五轮证明跳过 committed cache hit 的物理版本校验有明确收益，但它不能直接默认开启，
因为 restore / snapshot 安装后，同进程内旧的 committed row、row-count 和 rowId hint cache
可能继续指向 restore 前的数据。本轮先把这个风险边界落到代码里：

1. `TxnManager` 新增实例级 `trustCommittedRowCache` 开关，默认仍来自
   `-Dvexra.adb.rowCache.trustCommitted=true`，测试可以直接构造 trusted manager 覆盖该路径。
2. `TxnManager.invalidateStoreDerivedCaches()` 会同时清理 committed row cache、row-count cache
   和 max rowId hint。
3. `AdbRuntimeOperationsBridge` 新增可选 `TxnManager` 构造参数；当 runtime restore 成功后，
   会主动调用 `invalidateStoreDerivedCaches()`。
4. 旧的 `AdbRuntimeOperationsBridge(DbStore, AdbControlPlaneClient, String)` 构造函数保持兼容；
   未传入 manager 的调用方语义不变。

测试增强：

- `AdbRuntimeOperationsBridgeTest.shouldRunFullBackupAndRestoreDrill` 使用 trusted
  `TxnManager`：先 checkpoint `before-backup`，再提交并缓存 `after-backup`，restore 后必须读回
  `before-backup`。如果 restore 不清缓存，该用例会在 trusted cache 模式下返回旧的
  `after-backup`。

验证命令：

```powershell
.\gradlew.bat --% :vexra-adb:test --tests net.xdob.vexra.adb.db.AdbRuntimeOperationsBridgeTest --rerun-tasks
```

验证结果：通过。

trusted mixed 8 线程复跑命令：

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkJvmArgs=-Dvexra.adb.rowCache.trustCommitted=true -PadbBenchmarkWorkload=mixed -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=8 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_trusted_cache_restore_invalidation_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-mixed-trusted-cache-restore-invalidation-stage/adb-benchmark
```

结果：

| workload | mode | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | 结果文件 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| `mixed` | trusted cache, restore invalidation code | 2446.98 | 2370 | 8240 | 10900 | 663423 | `vexra-adb/build/adb-benchmark/jdbc_mixed_trusted_cache_restore_invalidation_stage.properties` |

结论：

- 本轮代码没有改 point lookup / range count 热路径，因此不把单次 mixed 结果作为新的性能收益结论。
- `allocation bytes/op` 与第二十五轮 trusted default 基本同量级，说明新增的 restore 失效边界没有给常规
  mixed 热路径带来额外对象分配。
- 单次 throughput 低于第二十五轮 trusted default，属于本轮需继续用多轮复跑确认的波动；由于代码只在
  restore 后执行缓存清理，优先判断为 benchmark 方差而不是热路径回退。
- 该阶段让 `trustCommitted` 从“纯压测开关”前进为“通过 runtime restore 可控失效的压测/局部优化开关”。
  下一步要继续把收益默认化，需要覆盖 region snapshot installer、直接 `DbStore.restore(...)` 调用和外部
  store 变更通知，或者引入 store generation。

## 第二十七轮：全表 COUNT(*) singleLong 结果集

prepared range count 在第十几轮已经改为 `AdbSimpleResultSet.singleLong(...)`，但普通
`SELECT COUNT(*) FROM table` 仍然通过 `ValueBigint + Value[] + singleRow(...)` 构造结果集。
本轮把 `AdbTableCountPlan` 也切换到 `singleLong("COUNT(*)", count)`：

1. 去掉 `AdbTableCountPlan` 中每次 count 快路径都会创建的 `ValueBigint` 和单元素 `Value[]`。
2. 保留 `COUNT(*)` 列名，继续支持 `findColumn("COUNT(*)")`、`getLong(1)` 和 `getLong("COUNT(*)")`。
3. 不改变 row-count meta、事务本地 delta 和 SQL fallback 语义。

验证命令：

```powershell
.\gradlew.bat --% :vexra-adb:test --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest --rerun-tasks
```

验证结果：通过。

benchmark：

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkWorkload=table_count -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=1 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/table_count_single_long_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/table-count-single-long-stage/adb-benchmark
```

结果：

| workload | threads | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | 结果文件 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `table_count` | 1 | 2158.27 | 436 | 661 | 952 | 9149 | `vexra-adb/build/adb-benchmark/table_count_single_long_stage.properties` |
| `table_count` | 8 | 3802.28 | 1842 | 3702 | 4967 | 9467 | `vexra-adb/build/adb-benchmark/jdbc_table_count_single_long_stage.properties` |

结论：

- 单线程 `table_count` 相比第十九轮 prewarm 基线 `1318.68 ops/s` 有明显提升，p99 从
  `1844us` 降到 `952us`。
- `allocation bytes/op` 与历史基线基本同量级，说明本轮主要减少的是 count 结果边界上的临时对象，
  整体分配仍由 JDBC proxy / statement / diagnostics 周边主导。
- 这完成了第 5 项中“COUNT 快路径继续使用专用 long ResultSet handler”的一部分；真正的
  “去掉动态代理的专用 ResultSet 类”仍应等待 JFR allocation 证据后再做。

## 第二十八轮：visible row raw scan 与历史版本 cache 保护

第二十四、二十五轮把 committed row read-fill cache 引入点查路径，但继续推进第 3 项时发现一个
必须先收紧的边界：当读事务 startTs 早于某个 row 的最新 committed 版本时，store scan 会跳过新版本并返回
旧版本；这个旧版本不能写入全局 committed row cache，否则后续更新的读事务可能被旧缓存误导。

本轮改动：

1. `TxnManager.getVisibleCommitted(...)` 对 row key 使用内部 raw-key scan，避免默认路径每次创建
   `DefaultVersionResolver` / `VersionKey` 对象。
2. raw scan 会记录是否曾遇到晚于当前事务 startTs 的 committed 版本；只有没有遇到更新版本时，
   才允许把读取到的 row 回填到 committed row cache。
3. detail 诊断路径保持原有 phase 拆分，但同样遵守“历史版本不回填全局 cache”的规则。
4. `cacheCommittedVisible(...)` 不再允许更旧 commitTs 覆盖已有更新 cache。
5. 新增 `TxnManagerVisibleRowFastPathTest`，直接构造 commitTs=10 / commitTs=20 的版本链，验证
   startTs=15 读到旧版本后，不会污染 startTs=25 的新读事务。

验证命令：

```powershell
.\gradlew.bat --% :vexra-adb:test --tests net.xdob.vexra.adb.db.TxnManagerVisibleRowFastPathTest --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest --rerun-tasks
```

验证结果：通过。

benchmark：

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkWorkload=point_lookup -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=1 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/point_lookup_visible_raw_scan_no_read_object_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/point-lookup-visible-raw-scan-no-read-object-stage/adb-benchmark
```

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkWorkload=mixed -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=8 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_visible_raw_scan_no_read_object_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-mixed-visible-raw-scan-no-read-object-stage/adb-benchmark
```

结果：

| workload | threads | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | 结果文件 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `point_lookup` | 1 | 1813.78 | 498 | 948 | 1312 | 10704 | `vexra-adb/build/adb-benchmark/point_lookup_visible_raw_scan_no_read_object_stage.properties` |
| `mixed` | 8 | 2228.83 | 2491 | 8839 | 12591 | 711434 | `vexra-adb/build/adb-benchmark/jdbc_mixed_visible_raw_scan_no_read_object_stage.properties` |

结论：

- 本轮主要是第 3 项的安全前置优化，不把 mixed 结果作为吞吐提升 headline；mixed 单次结果低于
  第二十四轮默认安全 cache 基线，需要后续继续排查和复跑。
- `point_lookup` 在去掉内部返回对象后恢复到可接受区间，但仍未超过历史最好样本。
- 正向价值是 committed read-fill cache 不再被历史快照污染，这为后续“latest committed cache / trusted cache
  默认化 / store generation”继续降 store seek 成本提供了更稳的语义基础。

## 第二十九轮：bulk append 批量唯一性判定

继续推进第 4 项 `BULK_ADD_ROW / ADD_ROW` 写入入口优化时，发现
`bulkInsertAppendRows` 仍然对每一行分别调用 `canSkipAppendUniqueCheck(rowKey)`。
本轮把多行 append 的判定提升到批量入口：

1. `TxnManager` 新增按 `TabId + rowId` 判断 append hint 的入口，避免整批判定时先构造
   每行 `RowKey` 再查 committed high-water。
2. `TxnMap2` 新增 `canSkipAppendUniqueChecks(tabId, minRowId, maxRowId)`，调用方完成批内去重后，
   只要本批 rowId 范围不与事务本地写集重叠，且整批最小 rowId 大于本事务或 committed high-water，
   就可整批跳过 committed 唯一性扫描。
3. `TxnMap2.putEncodedAppend(...)` 在 bulk 写入后登记事务内 append high-water，让同一事务后续追加批次继续命中 fast path。
4. 单行 bulk 入口保留专用分支，避免普通 `INSERT INTO ... VALUES (...)` 被多行批处理的
   `HashSet` 和两遍循环污染。
5. `canSkipAppendUniqueCheck(DataKey)` 先检查同一事务本地相同 key 写入，避免 update/delete
   后再插入同 key 时被 append high-water 误判。

新增测试：

- `rejectsDuplicatePrimaryKeyAcrossBulkBatchesInOneTransaction` 覆盖同一事务内第一批成功、
  第二批重复主键失败，并验证只回滚失败批次。
- `appendsMultipleBulkBatchesInOneTransaction` 覆盖同一事务多批连续 append。

验证命令：

```powershell
.\gradlew.bat --% :vexra-adb:test --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest --rerun-tasks
```

验证结果：通过。

benchmark：

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkWorkload=insert -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=1 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_insert_single_branch_bulk_append_batch_skip_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-insert-single-branch-bulk-append-batch-skip-stage/adb-benchmark
```

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkMode=jdbc_bulk -PadbBenchmarkWorkload=insert -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=1 -PadbBenchmarkStatementBatchSize=100 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_bulk_insert_single_branch_bulk_append_batch_skip_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-bulk-insert-single-branch-bulk-append-batch-skip-stage/adb-benchmark
```

```powershell
.\gradlew.bat --% :vexra-adb:adbBenchmark -PadbBenchmarkWorkload=mixed -PadbBenchmarkRows=5000 -PadbBenchmarkWarmupOperations=300 -PadbBenchmarkOperations=3000 -PadbBenchmarkThreads=8 -PadbBenchmarkOutput=vexra-adb/build/adb-benchmark/jdbc_mixed_single_branch_bulk_append_batch_skip_stage.properties -PadbBenchmarkUrl=jdbc:adb:ldb:D:/work/java2/vexra/vexra-adb/build/adb-benchmark/db/jdbc-mixed-single-branch-bulk-append-batch-skip-stage/adb-benchmark
```

结果：

| workload | mode | threads | throughput ops/s | p50 us | p95 us | p99 us | allocation bytes/op | 结果文件 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `insert` | `jdbc` | 1 | 802.78 | 1010 | 2449 | 3322 | 38034 | `vexra-adb/build/adb-benchmark/jdbc_insert_single_branch_bulk_append_batch_skip_stage.properties` |
| `insert` | `jdbc_bulk` | 1 | 54545.45 | 18 | 28 | 31 | 2838 | `vexra-adb/build/adb-benchmark/jdbc_bulk_insert_single_branch_bulk_append_batch_skip_stage.properties` |
| `mixed` | `jdbc` | 8 | 2572.90 | 2335 | 7853 | 10089 | 663424 | `vexra-adb/build/adb-benchmark/jdbc_mixed_single_branch_bulk_append_batch_skip_stage.properties` |

结论：

- 本轮完成了第 4 项中“单事务内多行 insert 合并唯一性检查”和“append-only 主键继续扩大 fast path”的一部分。
- `jdbc_bulk` 明确受益于批量入口，正式窗口只记录 30 次 `ADB_TABLE_BULK_ADD_ROW`，
  `allocation.bytesPerOperation` 降到约 `2.8KB/op`，说明批量直接入口仍是当前最高价值写入方式。
- mixed 8 线程从第二十八轮 `2228.83 ops/s` 回到 `2572.90 ops/s`，p99 从 `12591us`
  降到 `10089us`；这说明该改动没有给综合路径带来明显回退。
- 普通单行 `jdbc insert` 仍只有 `802.78 ops/s`，低于第十五轮 allocation 基线的
  `1004.35 ops/s`。本轮单行分支已经避免了多行批处理额外循环，因此下一阶段若继续优化写入，
  最有价值的方向不是再拆 bulk 唯一性判定，而是拆 `ADB_COMMIT_WRITE` / write batch / fsync
  路径，或让更多普通 JDBC 场景真正聚合成批量提交。
