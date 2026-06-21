# Vexra User Guide

This guide describes the current Vexra ADB runtime modes, JDBC URLs, SQL table engine parameters, scripts, and troubleshooting notes. It documents the implemented state and does not present later TiDB-like goals as already completed.

## 1. Modules and Runtime Modes

| Mode | Entry Point | Use Case | Current Status |
| --- | --- | --- | --- |
| Embedded JDBC | `org.h2.Driver` + `jdbc:adb:*` | Single-process development, unit tests, local checks | Available |
| SQL Server | `bin\adb-sql-server.bat` | Standalone JVM exposing TCP JDBC | Available |
| Region Node | `bin\adb-region-node.bat` | Local Raft region node for remote scan/write smoke tests | Available |
| Distributed SQL | `adb_table` + `WITH "adb.distributed.*"` | SQL Server accessing remote region nodes | Prototype available, explicit parameters required |

ADB does not copy the h2db SQL parser, JDBC, Server, or toolchain. It registers its own URL prefix and table provider through h2db plugins.

## 2. JDBC URLs

| URL | Description | Example |
| --- | --- | --- |
| `jdbc:adb:mem:<name>` | In-memory database with `adb_table` as the default table engine | `jdbc:adb:mem:demo;DB_CLOSE_DELAY=-1` |
| `jdbc:adb:ldb:<path>` | File-backed database using the legacy ADB-compatible entry, mapped to an h2db file URL | `jdbc:adb:ldb:D:/data/adb/demo;DB_CLOSE_DELAY=0` |
| `jdbc:adb:tcp://<host>:<port>/<db>` | Connect to ADB SQL Server | `jdbc:adb:tcp://127.0.0.1:9123/demo;DB_CLOSE_DELAY=0` |
| `jdbc:h2:*;DEFAULT_TABLE_ENGINE=adb_table` | Use an h2db URL directly and select the ADB table engine | `jdbc:h2:mem:demo;DEFAULT_TABLE_ENGINE=adb_table` |

Example:

```java
String url = "jdbc:adb:mem:demo;DB_CLOSE_DELAY=-1";
try (Connection connection = new org.h2.Driver().connect(url, new Properties())) {
  // Use the h2db JDBC API.
}
```

Notes:

- `jdbc:adb:*` is mapped to `jdbc:h2:*` and appends `DEFAULT_TABLE_ENGINE=adb_table` when the setting is not already present.
- `jdbc:adb:rocksdb:` is recognized as a historical compatibility prefix, but this guide recommends `jdbc:adb:ldb:` or direct h2db URLs for current usage.
- If the URL already contains `DEFAULT_TABLE_ENGINE`, the plugin does not override it.

## 3. ADB Tables

Explicit table creation:

```sql
CREATE TABLE NOTES(
  ID BIGINT,
  NAME VARCHAR
) ENGINE "adb_table";
```

When using `jdbc:adb:*` without overriding `DEFAULT_TABLE_ENGINE`, regular table creation defaults to the ADB table provider:

```sql
CREATE TABLE NOTES(ID BIGINT, NAME VARCHAR);
```

## 4. Embedded JDBC Example

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

Build the runtime package:

```powershell
.\gradlew.bat :vexra-adb:adbRuntimeDist
New-Item -ItemType Directory -Force .\build | Out-Null
Expand-Archive -Force .\vexra-adb\build\distributions\vexra-adb-0.1.0-SNAPSHOT-runtime.zip .\build\adb-runtime
```

Automatic orchestration plan entry:

```powershell
.\bin\adb-cluster-plan.bat --config .\run\cluster.properties --writeCatalog true
```

`cluster.properties` describes SQL Server, region nodes, Raft peers, and the shared catalog together. The command prints `[preflight]`, `[catalog]`, `[sql]`, and `[region]` sections. The `[sql]` and `[region]` sections are executable startup commands, and `--writeCatalog true` writes catalog properties to `adb.cluster.catalog.path`.

Secure installer templates use these configuration keys:

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

Runtime code rejects distributed configurations that disable TLS, authentication, or least privilege. Installer templates generate systemd unit text and Windows `sc.exe` command text. This phase does not issue certificates, create OS users, or install OS services.

Startup parameters:

| Parameter | Required | Description | Example |
| --- | --- | --- | --- |
| `--port` | Yes | TCP JDBC port | `--port 9123` |
| `--baseDir` | No | Database root directory | `--baseDir .\work\sql` |
| `--tcpAllowOthers` | No | Whether to allow non-localhost connections | `--tcpAllowOthers true` |
| `--ifNotExists` | No | h2db Server if-not-exists behavior | `--ifNotExists true` |
| `--ready` | No | Ready file written after startup | `--ready .\run\sql.ready` |
| `--stop` | No | Process exits after this file appears | `--stop .\run\sql.stop` |

Startup example:

```powershell
.\bin\adb-sql-server.bat --port 9123 --baseDir .\work\sql --ifNotExists true --ready .\run\sql.ready --stop .\run\sql.stop
```

TCP JDBC example:

```java
String url = "jdbc:adb:tcp://127.0.0.1:9123/manual;DB_CLOSE_DELAY=0";
try (Connection connection = new org.h2.Driver().connect(url, new Properties());
     Statement statement = connection.createStatement()) {
  statement.execute("CREATE TABLE IF NOT EXISTS ITEMS(NAME VARCHAR) ENGINE \"adb_table\"");
  statement.executeUpdate("INSERT INTO ITEMS(NAME) VALUES ('tcp-adb')");
}
```

## 6. Region Node

A region node stores remote region data and provides scan/write smoke capability through the Raft client.

Startup parameters:

| Parameter | Required | Description | Example |
| --- | --- | --- | --- |
| `--group` | Yes | Raft group id. It must be the same for all nodes in the group | `--group 11111111-1111-1111-1111-111111111111` |
| `--node` | Yes | Current node id | `--node n1` |
| `--peers` | Yes | Full peer list | `--peers n1@127.0.0.1:19001,n2@127.0.0.1:19002,n3@127.0.0.1:19003` |
| `--host` | Yes | Current node bind address | `--host 127.0.0.1` |
| `--port` | Yes | Current node bind port | `--port 19001` |
| `--storage` | Yes | Raft / region persistence directory | `--storage .\work\n1\storage` |
| `--cache` | Yes | Cache directory | `--cache .\work\n1\cache` |
| `--ready` | No | Ready file written after startup | `--ready .\run\n1.ready` |
| `--stop` | No | Process exits after this file appears | `--stop .\run\n1.stop` |

Three-node example:

```powershell
$group = "11111111-1111-1111-1111-111111111111"
$peers = "n1@127.0.0.1:19001,n2@127.0.0.1:19002,n3@127.0.0.1:19003"

.\bin\adb-region-node.bat --group $group --node n1 --peers $peers --host 127.0.0.1 --port 19001 --storage .\work\n1\storage --cache .\work\n1\cache --ready .\run\n1.ready --stop .\run\n1.stop
```

For `n2` and `n3`, keep the same `$group` and `$peers`, but replace `--node`, `--port`, directories, and ready/stop files.

## 7. Distributed SQL Parameters

Distributed SQL is explicitly enabled through `WITH` parameters on `adb_table`.

| Parameter | Default | Description |
| --- | --- | --- |
| `adb.distributed.sql` | `false` | Enables the distributed SQL path |
| `adb.distributed.split.row` | empty | Local test split rowId. Empty means one full-table range |
| `adb.distributed.table.id` | H2 local table id | Table id used by remote regions |
| `adb.distributed.table.epoch` | H2 local table epoch | Table epoch used by remote regions |
| `adb.distributed.scan.client` | `local` | `local` or `raft` |
| `adb.distributed.write.client` | `local` | `local` or `raft` |
| `adb.distributed.catalog.path` | empty | Shared catalog properties file that can resolve table id, epoch, Raft target, and readTs |
| `adb.distributed.catalog.table` | current SQL table name | Table-name override used for catalog lookup |
| `adb.distributed.raft.group` | empty | Required when Raft read or write is enabled |
| `adb.distributed.raft.peers` | empty | Required when Raft read or write is enabled |
| `adb.distributed.raft.dbName` | `adb` | Database name used by region nodes |
| `adb.distributed.scan.readTs` | current transaction startTs | Fixed read timestamp, currently used for smoke checks |
| `adb.distributed.scan.timeoutMillis` | `5000` | Scan timeout. `0` means unlimited |
| `adb.distributed.write.timeoutMillis` | `5000` | Write timeout. `0` means unlimited |

Shared catalog example:

```properties
adb.catalog.raft.group=11111111-1111-1111-1111-111111111111
adb.catalog.raft.peers=n1@127.0.0.1:19001,n2@127.0.0.1:19002,n3@127.0.0.1:19003
adb.catalog.raft.dbName=adb
adb.catalog.tso.readTs=20000
adb.catalog.table.TEST.id=1
adb.catalog.table.TEST.epoch=0
```

Remote read/write example:

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

## 8. Tests and Verification

Common test command:

```powershell
.\gradlew.bat :vexra-adb:test
```

Run only URL prefix and table provider tests:

```powershell
.\gradlew.bat :vexra-adb:test --tests net.xdob.vexra.adb.h2plugin.AdbJdbcUrlPrefixProviderTest --tests net.xdob.vexra.adb.h2plugin.AdbTableProviderIntegrationTest
```

Run only the remote region SQL smoke test:

```powershell
.\gradlew.bat :vexra-adb:test --tests net.xdob.vexra.adb.AdbSqlServerRemoteRegionScanSmokeTest
```

End-to-end cluster stress gate model:

```powershell
.\gradlew.bat :vexra-adb:test --tests net.xdob.vexra.adb.db.AdbEndToEndClusterStressGateTest
```

The gate requires the long-running stress report, fault-injection matrix, commit crash-injection gate, recovery drill gate, SQL/region read/write smoke, recovery drill, and rolling-upgrade drill to all pass. A real long-duration stress platform can feed data into the `AdbEndToEndClusterStressReport` shape.

## 9. Performance Benchmark

ADB provides a minimal benchmark entry point for creating an archivable local
performance baseline. The default `jdbc` mode uses a file-backed
`jdbc:adb:ldb:` database and does not use `mem` mode. To test SQL Server or
distributed SQL, pass an explicit `jdbc:adb:tcp://...` URL. When you need to
separate SQL / table engine / JDBC auto-commit overhead from the local store
cost, use `store` mode to bypass SQL and measure the local `LdbStore` wrapper.

Default ldb benchmark:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark
```

Default output:

```text
vexra-adb/build/adb-benchmark/adb-benchmark.properties
```

Common parameters:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  -PadbBenchmarkMode=jdbc `
  -PadbBenchmarkWorkload=mixed `
  -PadbBenchmarkRows=10000 `
  -PadbBenchmarkWarmupOperations=1000 `
  -PadbBenchmarkOperations=10000 `
  -PadbBenchmarkRangeSize=64 `
  -PadbBenchmarkTransactionBatchSize=1 `
  -PadbBenchmarkThreads=1
```

In `jdbc` mode, `-PadbBenchmarkTransactionBatchSize=1` means one SQL statement
per auto-commit transaction. If write throughput looks low, increase it (for
example to `100` or `1000`) to measure batched transaction behavior:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  -PadbBenchmarkMode=jdbc `
  -PadbBenchmarkWorkload=insert `
  -PadbBenchmarkTransactionBatchSize=100 `
  -PadbBenchmarkOperations=10000
```

Local store baseline:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  -PadbBenchmarkMode=store `
  -PadbBenchmarkStoreDir=vexra-adb/build/adb-benchmark/store-baseline `
  -PadbBenchmarkWorkload=mixed `
  -PadbBenchmarkRows=10000 `
  -PadbBenchmarkOperations=10000
```

Available workloads:

| workload | Description |
| --- | --- |
| `insert` | Single-thread sequential write / upsert |
| `point_lookup` | Primary-key point lookup |
| `point_lookup_all` | Primary-key `SELECT *` point lookup |
| `table_count` | Full-table `COUNT(*)` using ADB row-count metadata |
| `range_scan` | Primary-key range count scan |
| `mixed` | About 10% writes, 70% point lookups, and 20% range scans |

Test SQL Server or the remote distributed path:

```powershell
.\gradlew.bat :vexra-adb:adbBenchmark `
  -PadbBenchmarkUrl=jdbc:adb:tcp://127.0.0.1:9123/bench`;DB_CLOSE_DELAY=0 `
  -PadbBenchmarkWorkload=point_lookup `
  -PadbBenchmarkOperations=5000
```

The runtime package also includes `bin/adb-benchmark.bat` /
`bin/adb-benchmark`, with the same main-class parameters:

```powershell
.\bin\adb-benchmark.bat --url "jdbc:adb:ldb:.\work\bench\adb-benchmark;DB_CLOSE_DELAY=0" --workload mixed --rows 10000 --operations 10000 --output .\run\adb-benchmark.properties
```

In `jdbc` mode, `-PadbBenchmarkThreads=N` runs the same workload concurrently
through multiple JDBC connections. It is mainly intended to identify lock
contention, transaction commit, and shared lower-store bottlenecks; short local
runs should not be treated as production capacity.

The output properties include at least `mode`, `workload`, `url`, `operations`,
`failedOperations`, `durationMillis`, `throughputPerSecond`,
`p50LatencyMicros`, `p95LatencyMicros`, `p99LatencyMicros`,
`maxLatencyMicros`, `passed`, `concurrency.threads`, and
`concurrency.perThreadThroughputPerSecond`. When `threads > 1`, the report also
includes `concurrency.completedOperations`. These results can feed release evidence or a
future long-running stress platform, but a short single-node run is not a
replacement for multi-hour / multi-node stress testing.

For the current local baseline and optimization conclusions, see
[ADB Performance Baseline Report](adb-performance-benchmark.en.md).

## 10. Troubleshooting

| Symptom | Possible Cause | Check |
| --- | --- | --- |
| JDBC connection refused | SQL Server is not started or the port differs | Check the `--ready` file, port, and process logs |
| `SELECT` does not see remote writes | Catalog `readTs` is earlier than commit timestamp, or table id / epoch differs | Verify `adb.catalog.tso.readTs=20000`, `adb.catalog.table.TEST.id=1`, and `adb.catalog.table.TEST.epoch=0` in the catalog |
| Raft read/write timeout | Peers, group, ports, or region node count do not match | Confirm all 3 region nodes are ready and `$peers` is identical |
| Table is created as a regular h2db table | URL overrides `DEFAULT_TABLE_ENGINE` or `ENGINE "adb_table"` is missing | Use the default `jdbc:adb:*` entry or specify `ENGINE "adb_table"` explicitly |
| Parameter has no effect | A `WITH` parameter is misspelled | Parameter keys are case-insensitive, but copying the guide spelling is recommended |

## 11. Current Boundaries

- The current default capability is suitable for local development, integration tests, and distributed read/write path smoke checks.
- The SQL-to-region catalog/TSO prototype now supports a properties snapshot, but cluster configuration, service discovery, and region orchestration still need later phases.
- Automatic table/region metadata, persistent global TSO, transaction coordination, distributed optimizer plans, node scheduling, 2 data nodes + witness, highly available deployment, and the operations control plane remain planned work.
- This guide now covers secure installer templates, secure-default gates, and the end-to-end cluster stress gate model. A real authentication system, certificate issuance, auditing, quotas, backup/restore, and external long-duration stress execution still require separate designs or deployment-system integration.
