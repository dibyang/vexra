package net.xdob.vexra.adb.ha2;

import net.xdob.vexra.adb.db.AdbRegionCommitRequest;
import net.xdob.vexra.adb.db.AdbRegionMutation;
import net.xdob.vexra.adb.db.AdbRegionScanRequest;
import net.xdob.vexra.adb.db.AdbRpcRegionCommitClient;
import net.xdob.vexra.adb.db.Meta;
import net.xdob.vexra.adb.db.RowCodec;
import net.xdob.vexra.adb.db.RowValue;
import net.xdob.vexra.adb.key.DataKey;
import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.cluster.region.KeyRange;
import net.xdob.vexra.cluster.sql.RegionQueryResult;
import net.xdob.vexra.cluster.sql.RegionScanTask;
import net.xdob.vexra.protocol.RaftGroupId;
import net.xdob.vexra.protocol.RaftPeer;
import org.h2.value.ValueVarchar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ADB runtime 发行包 region node 脚本级多进程 smoke 测试。
 *
 * <p>该测试解压 `adbRuntimeDist` 生成的 zip，通过发行包内 `bin/adb-region-node` 脚本启动
 * 3 个独立 JVM，再通过真实 Raft/GRPC client 验证 ADB region 写入和扫描路径。</p>
 */
class AdbRuntimeRegionNodeDistributionSmokeTest {
  @TempDir
  Path tempDir;

  /**
   * 验证 runtime zip 解包后的 region node 脚本可以启动 3 节点 Raft/GRPC 数据路径。
   *
   * @throws Exception 解包、子进程启动、Raft/GRPC 访问或清理失败时抛出
   */
  @Test
  void shouldStartRegionNodesFromRuntimeDistributionScript()
      throws Exception {
    Path runtimeDir = tempDir.resolve("runtime");
    extract(runtimeZip(), runtimeDir);
    int[] ports = findFreePorts(3);
    RaftGroupId groupId = RaftGroupId.randomId();
    List<RaftPeer> peers = peers(ports);
    List<RegionProcessHandle> processes = new ArrayList<>();

    try {
      for (RaftPeer peer : peers) {
        processes.add(startRegionNodeScript(runtimeDir, groupId, peers, peer));
      }
      waitForReady(processes);
      try (RaftRClient rClient = new RaftRClient(clientProperties(groupId,
          peers));
           AdbRpcRegionCommitClient commitClient =
               new AdbRpcRegionCommitClient(
                   new AdbRaftRegionCommitTransport("adb", rClient),
                   TimeUnit.SECONDS.toMillis(30))) {
        commitAndScanEventually(commitClient,
            new AdbRaftRegionScanClient("adb", rClient), processes);
      }
    } catch (Exception e) {
      throw withProcessLogs(e, processes);
    } finally {
      stopProcesses(processes);
    }
  }

  private RegionProcessHandle startRegionNodeScript(Path runtimeDir,
      RaftGroupId groupId, List<RaftPeer> peers, RaftPeer peer)
      throws IOException {
    String nodeId = peer.getId().getId();
    Path nodeDir = tempDir.resolve("nodes").resolve(nodeId);
    Path ready = nodeDir.resolve("ready");
    Path stop = nodeDir.resolve("stop");
    Path script = regionNodeScript(runtimeDir);

    List<String> command = new ArrayList<>();
    if (isWindows()) {
      command.add("cmd.exe");
      command.add("/c");
    }
    command.add(script.toAbsolutePath().toString());
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
    command.add(ready.toString());
    command.add("--stop");
    command.add(stop.toString());

    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(script.getParent().toFile());
    builder.redirectErrorStream(true);
    Process process = builder.start();
    StringBuilder logText = new StringBuilder();
    Thread logReader = startLogReader(nodeId, process.getInputStream(),
        logText);
    return new RegionProcessHandle(nodeId, process, ready, stop, runtimeDir,
        logText,
        logReader);
  }

  private static void waitForReady(List<RegionProcessHandle> processes)
      throws Exception {
    long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
    for (RegionProcessHandle handle : processes) {
      while (!Files.exists(handle.ready)) {
        if (!handle.process.isAlive()) {
          fail("Region process exited before ready: " + handle.nodeId
              + System.lineSeparator() + readLog(handle));
        }
        if (System.currentTimeMillis() > deadline) {
          fail("Timed out waiting for region process: " + handle.nodeId
              + System.lineSeparator() + readLogs(processes));
        }
        Thread.sleep(200L);
      }
    }
  }

  private static void commitAndScanEventually(
      AdbRpcRegionCommitClient commitClient, AdbRaftRegionScanClient scanClient,
      List<RegionProcessHandle> processes) throws Exception {
    Exception last = null;
    for (int attempt = 0; attempt < 12; attempt++) {
      try {
        commitAndScanOnce(commitClient, scanClient, attempt + 1);
        return;
      } catch (Exception e) {
        last = e;
        if (!allAlive(processes)) {
          throw e;
        }
        Thread.sleep(500L);
      }
    }
    throw last;
  }

  private static void commitAndScanOnce(AdbRpcRegionCommitClient commitClient,
      AdbRaftRegionScanClient scanClient, long rowId) throws Exception {
    long txnId = 500 + rowId;
    long commitTs = 700 + rowId;
    RowKey rowKey = rowKey(rowId);
    AdbRegionCommitRequest request = new AdbRegionCommitRequest(
        "r1", 1, "node-a", txnId, txnId, commitTs, "r1", rowKey, 3000, true,
        Collections.singletonList((DataKey) rowKey),
        Collections.singletonList(new AdbRegionMutation(rowKey,
            rowValue(txnId, "runtime-region-script-smoke"))),
        Collections.<Meta>emptyList());

    commitClient.prewriteAsync(request).get(30, TimeUnit.SECONDS);
    commitClient.commitAsync(request).get(30, TimeUnit.SECONDS);

    RegionQueryResult result = scanClient.scanAsync(scanRequest(commitTs,
        rowId)).get(30, TimeUnit.SECONDS);

    assertEquals(1, result.getRows().size());
    assertEquals("runtime-region-script-smoke",
        result.getRows().get(0).get("payload"));
  }

  private static void stopProcesses(List<RegionProcessHandle> processes) {
    for (RegionProcessHandle handle : processes) {
      try {
        Files.createDirectories(handle.stop.getParent());
        Files.write(handle.stop, Collections.singletonList("stop"),
            StandardCharsets.UTF_8, StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING);
      } catch (IOException ignored) {
        // 清理阶段继续处理其他进程，避免一个 stop 文件失败造成 JVM 泄漏。
      }
    }
    for (RegionProcessHandle handle : processes) {
      try {
        if (!handle.process.waitFor(10, TimeUnit.SECONDS)) {
        destroyProcessTree(handle.process);
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
    if (!processes.isEmpty()) {
      killRuntimeJavaProcesses(processes.get(0).runtimeDir);
    }
    for (RegionProcessHandle handle : processes) {
      try {
        handle.process.waitFor(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        handle.process.destroyForcibly();
      }
    }
  }

  private static Thread startLogReader(String nodeId, InputStream input,
      StringBuilder logText) {
    Thread thread = new Thread(() -> readProcessLog(input, logText),
        "adb-runtime-region-" + nodeId + "-log-reader");
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
      List<RegionProcessHandle> processes) {
    AssertionError error = new AssertionError(cause.getMessage()
        + System.lineSeparator() + readLogs(processes), cause);
    return new Exception(error);
  }

  private static boolean allAlive(List<RegionProcessHandle> processes) {
    for (RegionProcessHandle handle : processes) {
      if (!handle.process.isAlive()) {
        return false;
      }
    }
    return true;
  }

  private static String readLogs(List<RegionProcessHandle> processes) {
    StringBuilder builder = new StringBuilder();
    for (RegionProcessHandle handle : processes) {
      builder.append(readLog(handle));
    }
    return builder.toString();
  }

  private static String readLog(RegionProcessHandle handle) {
    StringBuilder builder = new StringBuilder();
    builder.append("==== ").append(handle.nodeId).append(" ====")
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

  private static AdbRegionScanRequest scanRequest(long readTs, long rowId) {
    return new AdbRegionScanRequest(new RegionScanTask("r1",
        new KeyRange(rowKey(rowId).toBytes(), rowKey(rowId + 1).toBytes()),
        Collections.emptyList(), Collections.emptyList(), 0, readTs),
        7, readTs, false, 0);
  }

  private static RowValue rowValue(long txnId, String value) {
    RowValue rowValue = new RowValue();
    rowValue.txnId = txnId;
    rowValue.payload = RowCodec.encode(ValueVarchar.get(value));
    return rowValue;
  }

  private static RowKey rowKey(long rowId) {
    return RowKey.of(TabId.of(1, 0L), rowId);
  }

  private static Path runtimeZip() throws IOException {
    Path distributions = Paths.get("build", "distributions");
    if (!Files.isDirectory(distributions)) {
      distributions = Paths.get("vexra-adb", "build", "distributions");
    }
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(distributions,
        "*runtime*.zip")) {
      for (Path path : stream) {
        return path;
      }
    }
    throw new IOException("runtime distribution zip not found in "
        + distributions.toAbsolutePath());
  }

  private static void extract(Path zip, Path targetDir) throws IOException {
    Files.createDirectories(targetDir);
    try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zip))) {
      ZipEntry entry;
      while ((entry = input.getNextEntry()) != null) {
        Path target = targetDir.resolve(entry.getName()).normalize();
        if (!target.startsWith(targetDir)) {
          throw new IOException("Illegal zip entry: " + entry.getName());
        }
        if (entry.isDirectory()) {
          Files.createDirectories(target);
        } else {
          Files.createDirectories(target.getParent());
          Files.copy(input, target);
        }
        input.closeEntry();
      }
    }
  }

  private static Path regionNodeScript(Path runtimeDir) {
    String name = isWindows() ? "adb-region-node.bat" : "adb-region-node";
    return runtimeDir.resolve("bin").resolve(name);
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase().contains("win");
  }

  private static void destroyProcessTree(Process process) {
    if (isWindows()) {
      Long pid = processId(process);
      if (pid != null) {
        try {
          Process killer = new ProcessBuilder("taskkill", "/PID",
              String.valueOf(pid), "/T", "/F").start();
          killer.waitFor(10, TimeUnit.SECONDS);
          return;
        } catch (IOException | InterruptedException e) {
          if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
          }
        }
      }
    }
    process.destroy();
  }

  private static Long processId(Process process) {
    try {
      Object value = Process.class.getMethod("pid").invoke(process);
      return value instanceof Number ? ((Number) value).longValue() : null;
    } catch (ReflectiveOperationException | SecurityException e) {
      return null;
    }
  }

  private static void killRuntimeJavaProcesses(Path runtimeDir) {
    if (!isWindows()) {
      return;
    }
    String runtimePattern = "*" + runtimeDir.toAbsolutePath() + "*";
    String script = "$runtimePattern = '" + powershellQuote(runtimePattern)
        + "'; Get-CimInstance Win32_Process | Where-Object { "
        + "$_.CommandLine -like '*AdbRegionNodeMain*' -and "
        + "$_.CommandLine -like $runtimePattern } | ForEach-Object { "
        + "Stop-Process -Id $_.ProcessId -Force }";
    try {
      Process killer = new ProcessBuilder("powershell.exe", "-NoProfile",
          "-Command", script).start();
      killer.waitFor(10, TimeUnit.SECONDS);
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static String powershellQuote(String value) {
    return value.replace("'", "''");
  }

  private static final class RegionProcessHandle {
    private final String nodeId;
    private final Process process;
    private final Path ready;
    private final Path stop;
    private final Path runtimeDir;
    private final StringBuilder logText;
    private final Thread logReader;

    private RegionProcessHandle(String nodeId, Process process, Path ready,
        Path stop, Path runtimeDir, StringBuilder logText, Thread logReader) {
      this.nodeId = nodeId;
      this.process = process;
      this.ready = ready;
      this.stop = stop;
      this.runtimeDir = runtimeDir;
      this.logText = logText;
      this.logReader = logReader;
    }
  }
}
