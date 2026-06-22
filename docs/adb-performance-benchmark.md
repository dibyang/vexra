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
