package net.xdob.vexra.adb;

import net.xdob.vexra.adb.db.AdbRegionScanRequest;
import net.xdob.vexra.adb.db.DbStoreEngine;
import net.xdob.vexra.adb.db.KeyCodec;
import net.xdob.vexra.adb.ha2.AdbRaftRegionScanClient;
import net.xdob.vexra.adb.ha2.AdbRegionNodeMain;
import net.xdob.vexra.adb.ha2.RaftRClient;
import net.xdob.vexra.adb.key.RowPrefix;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.cluster.region.KeyRange;
import net.xdob.vexra.cluster.sql.RegionQueryResult;
import net.xdob.vexra.cluster.sql.RegionScanTask;
import net.xdob.vexra.protocol.RaftGroupId;
import net.xdob.vexra.protocol.RaftPeer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * SQL server 到远端 region node 的分布式 scan smoke 测试。
 *
 * <p>该测试覆盖 `ADB-Run-07`：SQL server 独立 JVM 通过 table-engine 参数创建远端
 * Raft scan client，读取父进程已通过 region commit 路径写入到 forked region node 的行。</p>
 */
class AdbSqlServerRemoteRegionScanSmokeTest {
  private static final String REMOTE_VALUE = "remote-region-sql";
  private static final int REMOTE_TABLE_ID = 1;

  @TempDir
  Path tempDir;

  /**
   * 验证 SQL server 可以通过远端 Raft region scan 读取 region node 数据。
   *
   * @throws Exception 子进程启动、region 写入或 SQL 查询失败时抛出
   */
  @Test
  void shouldWriteAndReadRemoteRegionRowsThroughSqlServer() throws Exception {
    int[] regionPorts = findFreePorts(3);
    int sqlPort = findFreePort();
    RaftGroupId groupId = RaftGroupId.randomId();
    List<RaftPeer> peers = peers(regionPorts);
    List<ProcessHandle> regionProcesses = new ArrayList<>();
    ProcessHandle sqlServer = null;
    String databaseName = "adb-remote-region-sql";
    Path sqlDir = tempDir.resolve("sql-server");
    Path databasePath = sqlDir.resolve(databaseName).toAbsolutePath();

    try {
      for (RaftPeer peer : peers) {
        regionProcesses.add(startRegionNode(groupId, peers, peer));
      }
      waitForReady(regionProcesses, "region node");

      sqlServer = startSqlServer(sqlPort, sqlDir);
      waitForReady(Collections.singletonList(sqlServer), "sql server");
      Path catalog = writeSharedCatalog(sqlDir, groupId, peers);
      executeRemoteSqlSmoke(sqlPort, databaseName, groupId, peers, catalog,
          regionProcesses);
      assertRemoteScanHasRowEventually(groupId, peers, regionProcesses);
    } catch (Exception e) {
      throw withProcessLogs(e, regionProcesses, sqlServer);
    } finally {
      if (sqlServer != null) {
        stopProcess(sqlServer);
      }
      stopProcesses(regionProcesses);
      DbStoreEngine.close(databasePath.toString().replace('\\', '/'));
    }
  }

  private ProcessHandle startRegionNode(RaftGroupId groupId,
      List<RaftPeer> peers, RaftPeer peer) throws IOException {
    String nodeId = peer.getId().getId();
    Path nodeDir = tempDir.resolve("region-nodes").resolve(nodeId);
    List<String> command = javaCommand(AdbRegionNodeMain.MAIN_CLASS);
    command.add("--group");
    command.add(groupId.toString());
    command.add("--node");
    command.add(nodeId);
    command.add("--peers");
    command.add(nodes(peers));
    command.add("--host");
    command.add("127.0.0.1");
    command.add("--port");
    command.add(String.valueOf(portOf(peer)));
    command.add("--storage");
    command.add(nodeDir.resolve("storage").toString());
    command.add("--cache");
    command.add(nodeDir.resolve("cache").toString());
    command.add("--ready");
    command.add(nodeDir.resolve("ready").toString());
    command.add("--stop");
    command.add(nodeDir.resolve("stop").toString());
    return startProcess(nodeId, command, nodeDir.resolve("ready"),
        nodeDir.resolve("stop"));
  }

  private ProcessHandle startSqlServer(int port, Path sqlDir)
      throws IOException {
    List<String> command = javaCommand(AdbSqlServerMain.MAIN_CLASS);
    command.add("--port");
    command.add(String.valueOf(port));
    command.add("--baseDir");
    command.add(sqlDir.toAbsolutePath().toString());
    command.add("--ifNotExists");
    command.add("true");
    command.add("--ready");
    command.add(sqlDir.resolve("ready").toString());
    command.add("--stop");
    command.add(sqlDir.resolve("stop").toString());
    return startProcess("sql-server", command, sqlDir.resolve("ready"),
        sqlDir.resolve("stop"));
  }

  private ProcessHandle startProcess(String name, List<String> command,
      Path ready, Path stop) throws IOException {
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.redirectErrorStream(true);
    Process process = builder.start();
    StringBuilder logText = new StringBuilder();
    Thread logReader = startLogReader(name, process.getInputStream(), logText);
    return new ProcessHandle(name, process, ready, stop, logText, logReader);
  }

  private static void executeRemoteSqlSmoke(int port, String databaseName,
      RaftGroupId groupId, List<RaftPeer> peers, Path catalog,
      List<ProcessHandle> regionProcesses) throws Exception {
    String url = "jdbc:adb:tcp://127.0.0.1:" + port + "/" + databaseName
        + ";DB_CLOSE_DELAY=0";
    try (Connection connection = new org.h2.Driver().connect(url,
        new Properties());
         Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE TEST(NAME VARCHAR) ENGINE \"adb_table\" WITH "
          + "\"adb.distributed.sql=true\", "
          + "\"adb.distributed.scan.client=raft\", "
          + "\"adb.distributed.write.client=raft\", "
          + "\"adb.distributed.catalog.path="
          + catalog.toAbsolutePath().toString().replace('\\', '/') + "\", "
          + "\"adb.distributed.write.timeoutMillis=30000\", "
          + "\"adb.distributed.scan.timeoutMillis=30000\"");
      statement.executeUpdate("INSERT INTO TEST(NAME) VALUES ('"
          + REMOTE_VALUE + "')");

      String explain = singleString(statement,
          "EXPLAIN SELECT NAME FROM TEST");
      assertTrue(explain.contains("ADB_DISTRIBUTED_SCAN"), explain);
      assertTrue(explain.contains("client=raft"), explain);
      assertTrue(explain.contains("tableId=" + REMOTE_TABLE_ID), explain);
      assertTrue(explain.contains("readTs=20000"), explain);

      assertEquals(REMOTE_VALUE, singleString(statement,
          "SELECT NAME FROM TEST"));
    }
  }

  private static Path writeSharedCatalog(Path sqlDir, RaftGroupId groupId,
      List<RaftPeer> peers) throws IOException {
    Path catalog = sqlDir.resolve("adb-shared-catalog.properties");
    Files.createDirectories(sqlDir);
    Files.write(catalog, Arrays.asList(
        "adb.catalog.raft.group=" + groupId,
        "adb.catalog.raft.peers=" + nodes(peers),
        "adb.catalog.raft.dbName=adb",
        "adb.catalog.tso.readTs=20000",
        "adb.catalog.table.TEST.id=" + REMOTE_TABLE_ID,
        "adb.catalog.table.TEST.epoch=0"), StandardCharsets.UTF_8,
        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    return catalog;
  }

  private static void assertRemoteScanHasRowEventually(RaftGroupId groupId,
      List<RaftPeer> peers, List<ProcessHandle> processes) throws Exception {
    AssertionError last = null;
    for (int attempt = 0; attempt < 12; attempt++) {
      try {
        assertRemoteScanHasRow(groupId, peers);
        return;
      } catch (AssertionError e) {
        last = e;
        if (!allAlive(processes)) {
          throw e;
        }
        Thread.sleep(500L);
      }
    }
    throw last;
  }

  private static void assertRemoteScanHasRow(RaftGroupId groupId,
      List<RaftPeer> peers) throws Exception {
    try (RaftRClient rClient = new RaftRClient(clientProperties(groupId,
        peers))) {
      AdbRaftRegionScanClient scanClient =
          new AdbRaftRegionScanClient("adb", rClient);
      byte[] tableStart = RowPrefix.of(TabId.of(REMOTE_TABLE_ID, 0L))
          .toBytes();
      RegionQueryResult result = scanClient.scanAsync(new AdbRegionScanRequest(
          new RegionScanTask("r1",
              new KeyRange(tableStart, KeyCodec.prefixEnd(tableStart)),
              Collections.emptyList(), Collections.emptyList(), 0, 20000),
          7, 20000, false, 0)).get(30, TimeUnit.SECONDS);
      assertEquals(1, result.getRows().size(),
          "direct remote scan should see committed row");
      assertTrue(String.valueOf(result.getRows().get(0).get("payload"))
          .contains(REMOTE_VALUE));
    }
  }

  private static String singleString(Statement statement, String sql)
      throws Exception {
    try (ResultSet resultSet = statement.executeQuery(sql)) {
      resultSet.next();
      return resultSet.getString(1);
    }
  }

  private static void waitForReady(List<ProcessHandle> processes, String label)
      throws Exception {
    long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
    for (ProcessHandle handle : processes) {
      while (!Files.exists(handle.ready)) {
        if (!handle.process.isAlive()) {
          fail(label + " process exited before ready: " + handle.name
              + System.lineSeparator() + readLog(handle));
        }
        if (System.currentTimeMillis() > deadline) {
          fail("Timed out waiting for " + label + ": " + handle.name
              + System.lineSeparator() + readLogs(processes));
        }
        Thread.sleep(200L);
      }
    }
  }

  private static void stopProcesses(List<ProcessHandle> processes) {
    for (ProcessHandle handle : processes) {
      stopProcess(handle);
    }
  }

  private static void stopProcess(ProcessHandle handle) {
    try {
      Files.createDirectories(handle.stop.getParent());
      Files.write(handle.stop, Collections.singletonList("stop"),
          StandardCharsets.UTF_8, StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING);
    } catch (IOException ignored) {
      // 清理阶段继续关闭进程，避免 stop 文件写入失败造成子进程泄漏。
    }
    try {
      if (!handle.process.waitFor(10, TimeUnit.SECONDS)) {
        handle.process.destroy();
        if (!handle.process.waitFor(5, TimeUnit.SECONDS)) {
          handle.process.destroyForcibly();
          handle.process.waitFor(10, TimeUnit.SECONDS);
        }
      }
      handle.logReader.join(TimeUnit.SECONDS.toMillis(2));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      handle.process.destroyForcibly();
    }
  }

  private static Thread startLogReader(String name, InputStream input,
      StringBuilder logText) {
    Thread thread = new Thread(() -> readProcessLog(input, logText),
        "adb-run07-" + name + "-log-reader");
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  private static void readProcessLog(InputStream input, StringBuilder logText) {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(input,
        StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        synchronized (logText) {
          logText.append(line).append(System.lineSeparator());
        }
      }
    } catch (IOException e) {
      synchronized (logText) {
        logText.append("failed to read process log: ")
            .append(e.getMessage()).append(System.lineSeparator());
      }
    }
  }

  private static Exception withProcessLogs(Exception cause,
      List<ProcessHandle> regionProcesses, ProcessHandle sqlServer) {
    List<ProcessHandle> handles = new ArrayList<>(regionProcesses);
    if (sqlServer != null) {
      handles.add(sqlServer);
    }
    AssertionError error = new AssertionError(cause.getMessage()
        + System.lineSeparator() + readLogs(handles), cause);
    return new Exception(error);
  }

  private static String readLogs(List<ProcessHandle> processes) {
    StringBuilder builder = new StringBuilder();
    for (ProcessHandle handle : processes) {
      builder.append(readLog(handle));
    }
    return builder.toString();
  }

  private static String readLog(ProcessHandle handle) {
    StringBuilder builder = new StringBuilder();
    builder.append("==== ").append(handle.name).append(" ====")
        .append(System.lineSeparator());
    synchronized (handle.logText) {
      if (handle.logText.length() == 0) {
        builder.append("log is empty");
      } else {
        builder.append(handle.logText);
      }
    }
    builder.append(System.lineSeparator());
    return builder.toString();
  }

  private static boolean allAlive(List<ProcessHandle> processes) {
    for (ProcessHandle handle : processes) {
      if (!handle.process.isAlive()) {
        return false;
      }
    }
    return true;
  }

  private static List<String> javaCommand(String mainClass) {
    List<String> command = new ArrayList<>();
    command.add(javaExecutable());
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(mainClass);
    return command;
  }

  private static String javaExecutable() {
    String name = System.getProperty("os.name", "")
        .toLowerCase().contains("win") ? "java.exe" : "java";
    return new File(new File(System.getProperty("java.home"), "bin"),
        name).getAbsolutePath();
  }

  private static Properties clientProperties(RaftGroupId groupId,
      List<RaftPeer> peers) {
    Properties properties = new Properties();
    properties.setProperty("HA2.GROUP", groupId.toString());
    properties.setProperty("HA2.NODES", nodes(peers));
    return properties;
  }

  private static List<RaftPeer> peers(int[] ports) {
    List<RaftPeer> peers = new ArrayList<>();
    for (int i = 0; i < ports.length; i++) {
      peers.add(RaftPeer.newBuilder()
          .setId("n" + (i + 1))
          .setAddress("127.0.0.1", ports[i])
          .build());
    }
    return peers;
  }

  private static String nodes(List<RaftPeer> peers) {
    StringBuilder builder = new StringBuilder();
    for (RaftPeer peer : peers) {
      if (builder.length() > 0) {
        builder.append(',');
      }
      builder.append(peer.getId().getId()).append('@').append(peer.getAddress());
    }
    return builder.toString();
  }

  private static int portOf(RaftPeer peer) {
    String address = peer.getAddress();
    return Integer.parseInt(address.substring(address.lastIndexOf(':') + 1));
  }

  private static int[] findFreePorts(int count) throws IOException {
    ServerSocket[] sockets = new ServerSocket[count];
    try {
      int[] ports = new int[count];
      for (int i = 0; i < count; i++) {
        sockets[i] = new ServerSocket(0);
        ports[i] = sockets[i].getLocalPort();
      }
      return ports;
    } finally {
      for (ServerSocket socket : sockets) {
        if (socket != null) {
          socket.close();
        }
      }
    }
  }

  private static int findFreePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static final class ProcessHandle {
    private final String name;
    private final Process process;
    private final Path ready;
    private final Path stop;
    private final StringBuilder logText;
    private final Thread logReader;

    private ProcessHandle(String name, Process process, Path ready, Path stop,
        StringBuilder logText, Thread logReader) {
      this.name = name;
      this.process = process;
      this.ready = ready;
      this.stop = stop;
      this.logText = logText;
      this.logReader = logReader;
    }
  }
}
