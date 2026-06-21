# Vexra 使用手册

本文说明 Vexra ADB 当前可用的运行模式、JDBC URL、SQL table engine 参数、运行脚本和常见问题。它描述的是当前实现能力，不把后续 TiDB 类目标提前包装成已完成能力。

## 1. 模块和运行模式

| 模式 | 入口 | 适用场景 | 当前状态 |
| --- | --- | --- | --- |
| 嵌入式 JDBC | `org.h2.Driver` + `jdbc:adb:*` | 单进程开发、单元测试、本地验证 | 可用 |
| SQL Server | `bin\adb-sql-server.bat` | 独立 JVM 提供 TCP JDBC 入口 | 可用 |
| Region Node | `bin\adb-region-node.bat` | 本地启动 Raft region node，供远端 scan/write smoke 使用 | 可用 |
| 分布式 SQL | `adb_table` + `WITH "adb.distributed.*"` | SQL Server 访问远端 region node | 原型可用，需显式参数 |

ADB 不复制 h2db 的 SQL parser、JDBC、Server 和工具链，而是通过 h2db 插件注册自己的 URL 前缀和 table provider。

## 2. JDBC URL

| URL | 说明 | 示例 |
| --- | --- | --- |
| `jdbc:adb:mem:<name>` | 内存库，自动使用 `adb_table` 作为默认表引擎 | `jdbc:adb:mem:demo;DB_CLOSE_DELAY=-1` |
| `jdbc:adb:ldb:<path>` | 文件库，ADB 旧入口兼容写法，映射到 h2db 文件 URL | `jdbc:adb:ldb:D:/data/adb/demo;DB_CLOSE_DELAY=0` |
| `jdbc:adb:tcp://<host>:<port>/<db>` | 连接 ADB SQL Server | `jdbc:adb:tcp://127.0.0.1:9123/demo;DB_CLOSE_DELAY=0` |
| `jdbc:h2:*;DEFAULT_TABLE_ENGINE=adb_table` | 直接使用 h2db URL 并指定 ADB table engine | `jdbc:h2:mem:demo;DEFAULT_TABLE_ENGINE=adb_table` |

示例：

```java
String url = "jdbc:adb:mem:demo;DB_CLOSE_DELAY=-1";
try (Connection connection = new org.h2.Driver().connect(url, new Properties())) {
  // 使用 h2db JDBC API。
}
```

注意事项：

- `jdbc:adb:*` 会映射到 `jdbc:h2:*`，并在未显式指定时追加 `DEFAULT_TABLE_ENGINE=adb_table`。
- `jdbc:adb:rocksdb:` 会被识别为历史兼容前缀，但当前运行手册建议优先使用 `jdbc:adb:ldb:` 或直接使用 h2db URL。
- 如果 URL 中已经包含 `DEFAULT_TABLE_ENGINE`，插件不会覆盖调用方的设置。

## 3. ADB 表

显式建表：

```sql
CREATE TABLE NOTES(
  ID BIGINT,
  NAME VARCHAR
) ENGINE "adb_table";
```

如果使用 `jdbc:adb:*` 且没有覆盖 `DEFAULT_TABLE_ENGINE`，普通建表也会默认进入 ADB table provider：

```sql
CREATE TABLE NOTES(ID BIGINT, NAME VARCHAR);
```

## 4. 嵌入式 JDBC 示例

```java
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

public class AdbEmbeddedExample {
  public static void main(String[] args) throws Exception {
    String url = "jdbc:adb:ldb:D:/work/java2/vexra/work/manual/embedded;DB_CLOSE_DELAY=0";
    try (Connection connection = new org.h2.Driver().connect(url, new Properties());
         Statement statement = connection.createStatement()) {
      statement.execute("DROP TABLE IF EXISTS USERS");
      statement.execute("CREATE TABLE USERS(ID BIGINT, NAME VARCHAR) ENGINE \"adb_table\"");
      statement.executeUpdate("INSERT INTO USERS(ID, NAME) VALUES (1, 'alice')");
      try (ResultSet rs = statement.executeQuery("SELECT NAME FROM USERS WHERE ID = 1")) {
        if (rs.next()) {
          System.out.println(rs.getString(1));
        }
      }
    }
  }
}
```

## 5. SQL Server

构建运行包：

```powershell
.\gradlew.bat :vexra-adb:adbRuntimeDist
New-Item -ItemType Directory -Force .\build | Out-Null
Expand-Archive -Force .\vexra-adb\build\distributions\vexra-adb-0.1.0-SNAPSHOT-runtime.zip .\build\adb-runtime
```

自动编排计划入口：

```powershell
.\bin\adb-cluster-plan.bat --config .\run\cluster.properties --writeCatalog true
```

`cluster.properties` 同时描述 SQL Server、region nodes、Raft peers 和共享 catalog。命令会输出 `[preflight]`、`[catalog]`、`[sql]` 和 `[region]` 四段；其中 `[sql]` 与 `[region]` 是可执行启动命令，`--writeCatalog true` 会把 catalog properties 写到 `adb.cluster.catalog.path`。

安全安装模板使用以下配置前缀：

```properties
adb.security.distributed=true
adb.security.tls.enabled=true
adb.security.auth.enabled=true
adb.security.leastPrivilege.enabled=true
adb.security.tls.ca=conf/ca.pem
adb.security.tls.certDir=conf/tls
adb.security.auth.tokenFile=conf/tokens.properties
adb.security.privilege.dir=conf/privileges
adb.security.serviceUser=vexra
```

运行时代码会拒绝分布式模式下关闭 TLS、认证或最小权限的配置；安装模板会生成 systemd unit 和 Windows `sc.exe` 命令文本。当前阶段不负责签发证书、创建系统用户或安装 OS 服务。

启动参数：

| 参数 | 必填 | 说明 | 示例 |
| --- | --- | --- | --- |
| `--port` | 是 | TCP JDBC 端口 | `--port 9123` |
| `--baseDir` | 否 | 数据库根目录 | `--baseDir .\work\sql` |
| `--tcpAllowOthers` | 否 | 是否允许非本机连接 | `--tcpAllowOthers true` |
| `--ifNotExists` | 否 | h2db Server 的 if-not-exists 语义 | `--ifNotExists true` |
| `--ready` | 否 | 启动完成后写入的 ready 文件 | `--ready .\run\sql.ready` |
| `--stop` | 否 | 发现该文件后退出 | `--stop .\run\sql.stop` |

启动示例：

```powershell
.\bin\adb-sql-server.bat --port 9123 --baseDir .\work\sql --ifNotExists true --ready .\run\sql.ready --stop .\run\sql.stop
```

TCP JDBC 示例：

```java
String url = "jdbc:adb:tcp://127.0.0.1:9123/manual;DB_CLOSE_DELAY=0";
try (Connection connection = new org.h2.Driver().connect(url, new Properties());
     Statement statement = connection.createStatement()) {
  statement.execute("CREATE TABLE IF NOT EXISTS ITEMS(NAME VARCHAR) ENGINE \"adb_table\"");
  statement.executeUpdate("INSERT INTO ITEMS(NAME) VALUES ('tcp-adb')");
}
```

## 6. Region Node

Region node 用于承载远端 region 数据，并通过 Raft 客户端提供 scan/write smoke 能力。

启动参数：

| 参数 | 必填 | 说明 | 示例 |
| --- | --- | --- | --- |
| `--group` | 是 | Raft group id。同一组节点必须一致 | `--group 11111111-1111-1111-1111-111111111111` |
| `--node` | 是 | 当前节点 id | `--node n1` |
| `--peers` | 是 | 完整 peer 列表 | `--peers n1@127.0.0.1:19001,n2@127.0.0.1:19002,n3@127.0.0.1:19003` |
| `--host` | 是 | 当前节点监听地址 | `--host 127.0.0.1` |
| `--port` | 是 | 当前节点监听端口 | `--port 19001` |
| `--storage` | 是 | Raft / region 持久化目录 | `--storage .\work\n1\storage` |
| `--cache` | 是 | 缓存目录 | `--cache .\work\n1\cache` |
| `--ready` | 否 | 启动完成后写入的 ready 文件 | `--ready .\run\n1.ready` |
| `--stop` | 否 | 发现该文件后退出 | `--stop .\run\n1.stop` |

3 节点示例：

```powershell
$group = "11111111-1111-1111-1111-111111111111"
$peers = "n1@127.0.0.1:19001,n2@127.0.0.1:19002,n3@127.0.0.1:19003"

.\bin\adb-region-node.bat --group $group --node n1 --peers $peers --host 127.0.0.1 --port 19001 --storage .\work\n1\storage --cache .\work\n1\cache --ready .\run\n1.ready --stop .\run\n1.stop
```

`n2` 和 `n3` 使用相同 `$group` / `$peers`，但替换 `--node`、`--port`、目录和 ready/stop 文件。

## 7. 分布式 SQL 参数

分布式 SQL 能力通过 `adb_table` 的 `WITH` 参数显式开启。

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `adb.distributed.sql` | `false` | 是否启用分布式 SQL 路径 |
| `adb.distributed.split.row` | 空 | 本地测试 split rowId；空表示单 region 全表范围 |
| `adb.distributed.table.id` | H2 本地 table id | 远端 region 使用的 table id |
| `adb.distributed.table.epoch` | H2 本地 table epoch | 远端 region 使用的 table epoch |
| `adb.distributed.scan.client` | `local` | `local` 或 `raft` |
| `adb.distributed.write.client` | `local` | `local` 或 `raft` |
| `adb.distributed.catalog.path` | 空 | 共享 catalog properties 文件；可补齐 table id、epoch、Raft 目标和 readTs |
| `adb.distributed.catalog.table` | 当前 SQL 表名 | catalog 中使用的表名覆盖值 |
| `adb.distributed.raft.group` | 空 | Raft 读或写为 `raft` 时必填 |
| `adb.distributed.raft.peers` | 空 | Raft 读或写为 `raft` 时必填 |
| `adb.distributed.raft.dbName` | `adb` | Region node 使用的数据库名 |
| `adb.distributed.scan.readTs` | 当前事务 startTs | 固定读取时间戳；当前用于 smoke 验证 |
| `adb.distributed.scan.timeoutMillis` | `5000` | scan 超时时间，`0` 表示不限制 |
| `adb.distributed.write.timeoutMillis` | `5000` | write 超时时间，`0` 表示不限制 |

共享 catalog 示例：

```properties
adb.catalog.raft.group=11111111-1111-1111-1111-111111111111
adb.catalog.raft.peers=n1@127.0.0.1:19001,n2@127.0.0.1:19002,n3@127.0.0.1:19003
adb.catalog.raft.dbName=adb
adb.catalog.tso.readTs=20000
adb.catalog.table.TEST.id=1
adb.catalog.table.TEST.epoch=0
```

远端读写示例：

```sql
CREATE TABLE TEST(NAME VARCHAR) ENGINE "adb_table" WITH
  "adb.distributed.sql=true",
  "adb.distributed.scan.client=raft",
  "adb.distributed.write.client=raft",
  "adb.distributed.catalog.path=D:/work/java2/vexra/build/adb-runtime/run/adb-catalog.properties",
  "adb.distributed.scan.timeoutMillis=30000",
  "adb.distributed.write.timeoutMillis=30000";

INSERT INTO TEST(NAME) VALUES ('remote-region-sql');
SELECT NAME FROM TEST;
```

## 8. 测试和验证

常用测试：

```powershell
.\gradlew.bat :vexra-adb:test
```

只跑 URL 前缀和 table provider 测试：

```powershell
.\gradlew.bat :vexra-adb:test --tests net.xdob.vexra.adb.h2plugin.AdbJdbcUrlPrefixProviderTest --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest
```

只跑远端 region SQL smoke：

```powershell
.\gradlew.bat :vexra-adb:test --tests net.xdob.vexra.adb.AdbSqlServerRemoteRegionScanSmokeTest
```

端到端集群压测门禁模型：

```powershell
.\gradlew.bat :vexra-adb:test --tests net.xdob.vexra.adb.db.AdbEndToEndClusterStressGateTest
```

门禁要求长稳压测报告、故障注入矩阵、commit crash-injection gate、recovery drill gate、SQL/region 读写 smoke、恢复演练和滚动升级演练全部满足验收。真实长时间压测平台可以按 `AdbEndToEndClusterStressReport` 的结构接入。

## 9. 性能基准

ADB 提供最小 benchmark 入口，用来生成可归档的本地性能基线。默认 `jdbc` 模式使用
`jdbc:adb:ldb:` 文件库，不使用 `mem` 模式；如果要测试 SQL Server 或分布式 SQL，可显式传入
`jdbc:adb:tcp://...` URL。需要判断瓶颈是否来自 SQL / table engine / JDBC auto-commit 时，可以用
`store` 模式绕过 SQL 层，直接测试本地 `LdbStore` 封装基线。

默认 ldb benchmark：

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark
```

默认输出：

```text
vexra-adb/build/adb-benchmark/adb-benchmark.properties
```

常用参数：

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  -PadbBenchmarkMode=jdbc `
  -PadbBenchmarkWorkload=mixed `
  -PadbBenchmarkRows=10000 `
  -PadbBenchmarkWarmupOperations=1000 `
  -PadbBenchmarkOperations=10000 `
  -PadbBenchmarkRangeSize=64 `
  -PadbBenchmarkTransactionBatchSize=1
```

`jdbc` 模式的 `-PadbBenchmarkTransactionBatchSize=1` 表示每条 SQL 自动提交。写入吞吐偏低时，
可以把它调大（例如 `100` 或 `1000`）测批量事务口径：

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  -PadbBenchmarkMode=jdbc `
  -PadbBenchmarkWorkload=insert `
  -PadbBenchmarkTransactionBatchSize=100 `
  -PadbBenchmarkOperations=10000
```

本地 store 基线：

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  -PadbBenchmarkMode=store `
  -PadbBenchmarkStoreDir=vexra-adb/build/adb-benchmark/store-baseline `
  -PadbBenchmarkWorkload=mixed `
  -PadbBenchmarkRows=10000 `
  -PadbBenchmarkOperations=10000
```

可选 workload：

| workload | 说明 |
| --- | --- |
| `insert` | 单线程顺序写入 / upsert |
| `point_lookup` | 主键点查 |
| `range_scan` | 主键范围计数扫描 |
| `mixed` | 约 10% 写入、70% 点查、20% 范围扫描 |

测试 SQL Server 或远端分布式路径：

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  -PadbBenchmarkUrl=jdbc:adb:tcp://127.0.0.1:9123/bench`;DB_CLOSE_DELAY=0 `
  -PadbBenchmarkWorkload=point_lookup `
  -PadbBenchmarkOperations=5000
```

runtime 包也包含 `bin/adb-benchmark.bat` / `bin/adb-benchmark`，参数与 main class 一致：

```powershell
.\bin\adb-benchmark.bat --url "jdbc:adb:ldb:.\work\bench\adb-benchmark;DB_CLOSE_DELAY=0" --workload mixed --rows 10000 --operations 10000 --output .\run\adb-benchmark.properties
```

输出 properties 至少包含：`mode`、`workload`、`url`、`operations`、`failedOperations`、
`durationMillis`、`throughputPerSecond`、`p50LatencyMicros`、`p95LatencyMicros`、
`p99LatencyMicros`、`maxLatencyMicros` 和 `passed`。这些结果可以作为 release evidence
或后续长稳平台的输入，但单机短跑不能替代多小时/多节点压测。

当前本地基线和优化判断见 [ADB 性能基线报告](adb-performance-benchmark.md)。

## 10. 故障排查

| 现象 | 可能原因 | 排查方式 |
| --- | --- | --- |
| JDBC 连接被拒绝 | SQL Server 未启动或端口不一致 | 检查 `--ready` 文件、端口和进程日志 |
| `SELECT` 查不到远端写入 | catalog 中的 `readTs` 早于提交时间戳，或 table id / epoch 不一致 | 确认 catalog 中 `adb.catalog.tso.readTs=20000`、`adb.catalog.table.TEST.id=1`、`adb.catalog.table.TEST.epoch=0` |
| Raft 读写超时 | peers、group、端口或 region node 数量不匹配 | 确认 3 个 region node 都已 ready，且 `$peers` 完全一致 |
| 建表进入普通 h2db 表 | URL 覆盖了 `DEFAULT_TABLE_ENGINE` 或未指定 `ENGINE "adb_table"` | 使用 `jdbc:adb:*` 默认入口，或显式指定 `ENGINE "adb_table"` |
| 参数未生效 | `WITH` 参数拼写错误 | 参数 key 大小写不敏感，但建议按本文写法复制 |

## 11. 当前边界

- 当前默认能力适合本地开发、集成测试和分布式读写链路 smoke。
- SQL 到 region 的 catalog/TSO 原型已支持 properties 快照，但集群配置、节点发现和 region 编排仍需后续阶段补齐。
- 自动 table/region 元数据、持久化全局 TSO、事务协调、SQL 优化器分布式计划、节点调度、2 数据节点 + witness、高可用部署和运维控制面仍在后续规划中。
- 本文已覆盖安全安装模板、默认安全门禁和端到端集群压测门禁模型；真实鉴权系统、证书签发、审计、配额、备份恢复和外部长时间压测执行仍需要单独设计或部署系统集成。
