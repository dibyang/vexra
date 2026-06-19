# Vexra 快速入门

本文面向第一次运行 Vexra ADB 的开发者，目标是在本地完成单机 JDBC 验证、SQL Server 验证，以及一个显式 opt-in 的远端 region SQL 读写示例。

当前 ADB 复用 h2db 的 SQL parser、JDBC、Server 和工具链，通过插件注册 `jdbc:adb:*` 前缀与 `adb_table` table engine。默认模式仍是单机本地表能力；远端 region SQL 读写需要在建表时显式配置 Raft 参数。

## 前置条件

- JDK 8。
- Windows PowerShell。
- 当前仓库工作目录为 `D:\work\java2\vexra`。
- 本地端口示例使用 `9123`、`19001`、`19002`、`19003`，如被占用请替换为空闲端口。

## 1. 构建

```powershell
.\gradlew.bat clean assemble
```

只构建 ADB 运行包时可以执行：

```powershell
.\gradlew.bat :vexra-adb:adbRuntimeDist
```

运行包生成在：

```text
vexra-adb\build\distributions\vexra-adb-0.1.0-SNAPSHOT-runtime.zip
```

解压到后续示例使用的目录：

```powershell
New-Item -ItemType Directory -Force .\build | Out-Null
Expand-Archive -Force .\vexra-adb\build\distributions\vexra-adb-0.1.0-SNAPSHOT-runtime.zip .\build\adb-runtime
```

解压后会得到：

```text
bin\adb-sql-server.bat
bin\adb-region-node.bat
lib\*.jar
```

后续示例假设运行包已解压到：

```text
D:\work\java2\vexra\build\adb-runtime
```

## 2. 单机 JDBC 示例

最小 JDBC 连接可以直接使用 `jdbc:adb:mem:`。`jdbc:adb:*` 会由 ADB 的 h2db 插件映射到 h2db 连接串，并默认追加 `DEFAULT_TABLE_ENGINE=adb_table`。

```java
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

public class AdbLocalQuickstart {
  public static void main(String[] args) throws Exception {
    String url = "jdbc:adb:mem:quickstart;DB_CLOSE_DELAY=-1";
    try (Connection connection = new org.h2.Driver().connect(url, new Properties());
         Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE IF NOT EXISTS NOTES(NAME VARCHAR) ENGINE \"adb_table\"");
      statement.executeUpdate("INSERT INTO NOTES(NAME) VALUES ('hello-adb')");
      try (ResultSet rs = statement.executeQuery("SELECT NAME FROM NOTES")) {
        while (rs.next()) {
          System.out.println(rs.getString(1));
        }
      }
    }
  }
}
```

文件库可以使用 `jdbc:adb:ldb:`：

```java
String url = "jdbc:adb:ldb:D:/work/java2/vexra/work/quickstart/db;DB_CLOSE_DELAY=0";
```

## 3. 启动 SQL Server

先准备目录：

```powershell
New-Item -ItemType Directory -Force .\run, .\work\sql | Out-Null
```

在解压后的运行包目录启动 SQL Server：

```powershell
cd D:\work\java2\vexra\build\adb-runtime
.\bin\adb-sql-server.bat --port 9123 --baseDir .\work\sql --ifNotExists true --ready .\run\sql.ready --stop .\run\sql.stop
```

另开一个 PowerShell 窗口，用 TCP JDBC URL 连接：

```java
String url = "jdbc:adb:tcp://127.0.0.1:9123/quickstart;DB_CLOSE_DELAY=0";
```

停止 SQL Server：

```powershell
New-Item -ItemType File -Force .\run\sql.stop | Out-Null
```

## 4. 启动 3 个 Region Node

远端 region 示例需要 3 个本地 region node。先选择一个 group id 和 peer 列表：

```powershell
$group = "11111111-1111-1111-1111-111111111111"
$peers = "n1@127.0.0.1:19001,n2@127.0.0.1:19002,n3@127.0.0.1:19003"
```

分别在 3 个 PowerShell 窗口启动：

```powershell
.\bin\adb-region-node.bat --group $group --node n1 --peers $peers --host 127.0.0.1 --port 19001 --storage .\work\n1\storage --cache .\work\n1\cache --ready .\run\n1.ready --stop .\run\n1.stop
```

```powershell
.\bin\adb-region-node.bat --group $group --node n2 --peers $peers --host 127.0.0.1 --port 19002 --storage .\work\n2\storage --cache .\work\n2\cache --ready .\run\n2.ready --stop .\run\n2.stop
```

```powershell
.\bin\adb-region-node.bat --group $group --node n3 --peers $peers --host 127.0.0.1 --port 19003 --storage .\work\n3\storage --cache .\work\n3\cache --ready .\run\n3.ready --stop .\run\n3.stop
```

确认 ready 文件存在后再执行远端 SQL：

```powershell
Test-Path .\run\n1.ready
Test-Path .\run\n2.ready
Test-Path .\run\n3.ready
```

## 5. 远端 Region SQL 读写示例

先启动 SQL Server，然后使用 `jdbc:adb:tcp://127.0.0.1:9123/quickstart;DB_CLOSE_DELAY=0` 建表：

```sql
CREATE TABLE TEST(NAME VARCHAR) ENGINE "adb_table" WITH
  "adb.distributed.sql=true",
  "adb.distributed.scan.client=raft",
  "adb.distributed.write.client=raft",
  "adb.distributed.table.id=1",
  "adb.distributed.table.epoch=0",
  "adb.distributed.raft.group=11111111-1111-1111-1111-111111111111",
  "adb.distributed.raft.peers=n1@127.0.0.1:19001,n2@127.0.0.1:19002,n3@127.0.0.1:19003",
  "adb.distributed.scan.readTs=20000",
  "adb.distributed.scan.timeoutMillis=30000",
  "adb.distributed.write.timeoutMillis=30000";

INSERT INTO TEST(NAME) VALUES ('remote-region-sql');

EXPLAIN SELECT NAME FROM TEST;
SELECT NAME FROM TEST;
```

`EXPLAIN` 中应能看到 `ADB_DISTRIBUTED_SCAN`、`client=raft`、`tableId=1`、`readTs=20000` 等信息。

## 6. 停止进程

```powershell
New-Item -ItemType File -Force .\run\sql.stop | Out-Null
New-Item -ItemType File -Force .\run\n1.stop | Out-Null
New-Item -ItemType File -Force .\run\n2.stop | Out-Null
New-Item -ItemType File -Force .\run\n3.stop | Out-Null
```

## 7. 验证命令

运行 ADB 模块测试：

```powershell
.\gradlew.bat :vexra-adb:test
```

只运行 SQL Server 到远端 region 的 smoke test：

```powershell
.\gradlew.bat :vexra-adb:test --tests net.xdob.vexra.adb.AdbSqlServerRemoteRegionScanSmokeTest
```

## 当前限制

- `jdbc:adb:*` 前缀已经由 h2db 插件支持，但真正连接、SQL 解析、Server 和工具链仍由 h2db 提供。
- 远端 region SQL 读写目前需要显式 `WITH` 参数，尚未接入自动 catalog、自动 table id 分配和全局 TSO。
- `adb.distributed.scan.readTs` 示例使用固定读时间戳，只适合 smoke 验证；生产形态需要后续 TSO 与事务快照规划。
- 当前示例是本地开发验证，不包含鉴权、TLS、节点发现、自动拉起、自动扩缩容和运维控制面。
