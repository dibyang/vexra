# Vexra Quickstart

This guide is for developers running Vexra ADB for the first time. It walks through local JDBC, SQL Server, and an explicit opt-in remote region SQL read/write smoke example.

ADB reuses the h2db SQL parser, JDBC, Server, and toolchain. It registers the `jdbc:adb:*` prefix and the `adb_table` table engine through h2db plugins. The default mode is still local single-node table behavior. Remote region SQL read/write requires explicit Raft parameters in the table definition.

## Prerequisites

- JDK 8.
- Windows PowerShell.
- Repository working directory: `D:\work\java2\vexra`.
- The examples use ports `9123`, `19001`, `19002`, and `19003`. Replace them if they are already in use.

## 1. Build

```powershell
.\gradlew.bat clean assemble
```

To build only the ADB runtime package:

```powershell
.\gradlew.bat :vexra-adb:adbRuntimeDist
```

The runtime package is generated at:

```text
vexra-adb\build\distributions\vexra-adb-0.1.0-SNAPSHOT-runtime.zip
```

Extract it to the directory used by the following examples:

```powershell
New-Item -ItemType Directory -Force .\build | Out-Null
Expand-Archive -Force .\vexra-adb\build\distributions\vexra-adb-0.1.0-SNAPSHOT-runtime.zip .\build\adb-runtime
```

After extraction, it contains:

```text
bin\adb-sql-server.bat
bin\adb-region-node.bat
lib\*.jar
```

The following examples assume it has been extracted to:

```text
D:\work\java2\vexra\build\adb-runtime
```

## 2. Local JDBC Example

The minimal JDBC connection can use `jdbc:adb:mem:` directly. The ADB h2db plugin maps `jdbc:adb:*` to an h2db URL and appends `DEFAULT_TABLE_ENGINE=adb_table` by default.

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

For a file-backed database, use `jdbc:adb:ldb:`:

```java
String url = "jdbc:adb:ldb:D:/work/java2/vexra/work/quickstart/db;DB_CLOSE_DELAY=0";
```

## 3. Start SQL Server

Prepare directories first:

```powershell
New-Item -ItemType Directory -Force .\run, .\work\sql | Out-Null
```

Start SQL Server from the extracted runtime directory:

```powershell
cd D:\work\java2\vexra\build\adb-runtime
.\bin\adb-sql-server.bat --port 9123 --baseDir .\work\sql --ifNotExists true --ready .\run\sql.ready --stop .\run\sql.stop
```

Open another PowerShell window and connect with a TCP JDBC URL:

```java
String url = "jdbc:adb:tcp://127.0.0.1:9123/quickstart;DB_CLOSE_DELAY=0";
```

Stop SQL Server:

```powershell
New-Item -ItemType File -Force .\run\sql.stop | Out-Null
```

## 4. Start 3 Region Nodes

The remote region example requires 3 local region nodes. Choose a group id and peer list first:

```powershell
$group = "11111111-1111-1111-1111-111111111111"
$peers = "n1@127.0.0.1:19001,n2@127.0.0.1:19002,n3@127.0.0.1:19003"
```

Start each node in a separate PowerShell window:

```powershell
.\bin\adb-region-node.bat --group $group --node n1 --peers $peers --host 127.0.0.1 --port 19001 --storage .\work\n1\storage --cache .\work\n1\cache --ready .\run\n1.ready --stop .\run\n1.stop
```

```powershell
.\bin\adb-region-node.bat --group $group --node n2 --peers $peers --host 127.0.0.1 --port 19002 --storage .\work\n2\storage --cache .\work\n2\cache --ready .\run\n2.ready --stop .\run\n2.stop
```

```powershell
.\bin\adb-region-node.bat --group $group --node n3 --peers $peers --host 127.0.0.1 --port 19003 --storage .\work\n3\storage --cache .\work\n3\cache --ready .\run\n3.ready --stop .\run\n3.stop
```

Check that ready files exist before running remote SQL:

```powershell
Test-Path .\run\n1.ready
Test-Path .\run\n2.ready
Test-Path .\run\n3.ready
```

## 5. Remote Region SQL Read/Write Example

Prepare a shared catalog file first:

```powershell
@"
adb.catalog.raft.group=11111111-1111-1111-1111-111111111111
adb.catalog.raft.peers=n1@127.0.0.1:19001,n2@127.0.0.1:19002,n3@127.0.0.1:19003
adb.catalog.raft.dbName=adb
adb.catalog.tso.readTs=20000
adb.catalog.table.TEST.id=1
adb.catalog.table.TEST.epoch=0
"@ | Set-Content -Encoding UTF8 .\run\adb-catalog.properties
```

Then start SQL Server and create a table through `jdbc:adb:tcp://127.0.0.1:9123/quickstart;DB_CLOSE_DELAY=0`:

```sql
CREATE TABLE TEST(NAME VARCHAR) ENGINE "adb_table" WITH
  "adb.distributed.sql=true",
  "adb.distributed.scan.client=raft",
  "adb.distributed.write.client=raft",
  "adb.distributed.catalog.path=D:/work/java2/vexra/build/adb-runtime/run/adb-catalog.properties",
  "adb.distributed.scan.timeoutMillis=30000",
  "adb.distributed.write.timeoutMillis=30000";

INSERT INTO TEST(NAME) VALUES ('remote-region-sql');

EXPLAIN SELECT NAME FROM TEST;
SELECT NAME FROM TEST;
```

`EXPLAIN` should include `ADB_DISTRIBUTED_SCAN`, `client=raft`, `tableId=1`, and `readTs=20000`. The table id, epoch, Raft target, and readTs come from the catalog file and no longer need to be written in SQL.

## 6. Stop Processes

```powershell
New-Item -ItemType File -Force .\run\sql.stop | Out-Null
New-Item -ItemType File -Force .\run\n1.stop | Out-Null
New-Item -ItemType File -Force .\run\n2.stop | Out-Null
New-Item -ItemType File -Force .\run\n3.stop | Out-Null
```

## 7. Verification Commands

Run the ADB module tests:

```powershell
.\gradlew.bat :vexra-adb:test
```

Run only the SQL Server to remote region smoke test:

```powershell
.\gradlew.bat :vexra-adb:test --tests net.xdob.vexra.adb.AdbSqlServerRemoteRegionScanSmokeTest
```

## Current Limitations

- The `jdbc:adb:*` prefix is supported through h2db plugins, but h2db still provides the actual connection, SQL parser, Server, and toolchain.
- Remote region SQL read/write still needs explicit `adb.distributed.sql=true`, but table id, epoch, Raft target, and readTs can now be resolved from the shared catalog/TSO prototype.
- The catalog example uses a fixed read timestamp for smoke verification only. A production shape still needs persistent TSO, transaction snapshots, and control-plane leases.
- These examples are local development checks. Authentication, TLS, discovery, process orchestration, autoscaling, and an operations control plane are not included.
