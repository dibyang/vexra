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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ADB 真实 OS 多进程 Raft/GRPC region RPC smoke 测试。
 *
 * <p>该测试由父 JUnit 进程 fork 3 个独立 JVM，每个子进程启动 1 个真实
 * `RaftServer` 和 `AdbStateMachine`。父进程随后通过 {@link RaftRClient}
 * 访问这组节点，覆盖 prewrite、commit 和 region scan。该测试补齐
 * `ADB-Prod-01` 的进程级部署验收边界，但仍不替代生产启动脚本、安全配置和长稳压测。</p>
 */
class AdbMultiProcessRaftRegionRpcSmokeTest {
  @TempDir
  private Path tempDir;

  /**
   * 验证 3 个独立 JVM 中的 Raft/GRPC 节点可以提交并读取 ADB 可见行。
   */
  @Test
  void shouldCommitAndScanVisibleRowThroughOsProcesses() throws Exception {
    int[] ports = findFreePorts(3);
    RaftGroupId groupId = RaftGroupId.randomId();
    List<RaftPeer> peers = peers(ports);
    List<ServerProcessHandle> processes = new ArrayList<>();

    try {
      for (RaftPeer peer : peers) {
        processes.add(startServerProcess(groupId, peers, peer));
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

  /**
   * 启动单个 ADB Raft region server 子进程。
   *
   * @param groupId 当前测试 Raft group ID
   * @param peers 当前测试 Raft peer 列表
   * @param peer 当前要启动的 peer
   * @return 子进程句柄
   * @throws IOException 子进程启动失败时抛出
   */
  private ServerProcessHandle startServerProcess(RaftGroupId groupId,
      List<RaftPeer> peers, RaftPeer peer) throws IOException {
    String nodeId = peer.getId().getId();
    Path nodeDir = tempDir.resolve("nodes").resolve(nodeId);
    Path ready = nodeDir.resolve("ready");
    Path stop = nodeDir.resolve("stop");

    List<String> args = new ArrayList<>();
    args.add("--group");
    args.add(groupId.toString());
    args.add("--node");
    args.add(nodeId);
    args.add("--peers");
    args.add(nodes(peers));
    args.add("--port");
    args.add(String.valueOf(portOf(peer)));
    args.add("--storage");
    args.add(nodeDir.resolve("storage").toString());
    args.add("--cache");
    args.add(nodeDir.resolve("cache").toString());
    args.add("--ready");
    args.add(ready.toString());
    args.add("--stop");
    args.add(stop.toString());

    List<String> command = new ArrayList<>();
    command.add(javaExecutable());
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(AdbRaftRegionServerProcess.class.getName());
    command.addAll(args);
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.redirectErrorStream(true);
    Process process = builder.start();
    StringBuilder logText = new StringBuilder();
    Thread logReader = startLogReader(nodeId, process.getInputStream(),
        logText);
    return new ServerProcessHandle(nodeId, process, ready, stop, logText,
        logReader);
  }

  /**
   * 启动后台日志读取线程，避免子进程输出阻塞并避免 Windows 文件句柄影响临时目录清理。
   *
   * @param nodeId 当前节点 ID
   * @param input 子进程合并后的 stdout/stderr
   * @param logText 内存日志缓冲
   * @return 已启动的日志读取线程
   */
  private static Thread startLogReader(String nodeId, InputStream input,
      StringBuilder logText) {
    Thread thread = new Thread(() -> readProcessLog(input, logText),
        "adb-raft-region-" + nodeId + "-log-reader");
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  /**
   * 持续读取子进程日志到内存缓冲。
   *
   * @param input 子进程输出流
   * @param logText 内存日志缓冲
   */
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

  /**
   * 等待所有子进程写入 ready 文件。
   *
   * @param processes 子进程句柄列表
   * @throws Exception 等待超时或子进程提前退出时抛出
   */
  private void waitForReady(List<ServerProcessHandle> processes)
      throws Exception {
    long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
    for (ServerProcessHandle handle : processes) {
      while (!Files.exists(handle.ready)) {
        if (!handle.process.isAlive()) {
          fail("Server process exited before ready: " + handle.nodeId
              + System.lineSeparator() + readLog(handle));
        }
        if (System.currentTimeMillis() > deadline) {
          fail("Timed out waiting for server process: " + handle.nodeId
              + System.lineSeparator() + readLogs(processes));
        }
        Thread.sleep(200L);
      }
    }
  }

  /**
   * 尝试多次提交和扫描，吸收 Raft leader 选举初期的短暂不可用。
   *
   * @param commitClient commit client
   * @param scanClient scan client
   * @param processes 子进程句柄，用于失败诊断
   * @throws Exception 多次重试后仍失败时抛出最后一次异常
   */
  private static void commitAndScanEventually(
      AdbRpcRegionCommitClient commitClient, AdbRaftRegionScanClient scanClient,
      List<ServerProcessHandle> processes) throws Exception {
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

  /**
   * 执行一轮 prewrite、commit 和 region scan 验证。
   *
   * @param commitClient commit client
   * @param scanClient scan client
   * @param rowId 本轮写入的 row id
   * @throws Exception 写入或读取失败时抛出
   */
  private static void commitAndScanOnce(AdbRpcRegionCommitClient commitClient,
      AdbRaftRegionScanClient scanClient, long rowId) throws Exception {
    long txnId = 100 + rowId;
    long commitTs = 200 + rowId;
    RowKey rowKey = rowKey(rowId);
    AdbRegionCommitRequest request = new AdbRegionCommitRequest(
        "r1", 1, "node-a", txnId, txnId, commitTs, "r1", rowKey, 3000, true,
        Collections.singletonList((DataKey) rowKey),
        Collections.singletonList(new AdbRegionMutation(rowKey,
            rowValue(txnId, "raft-grpc-process-smoke"))),
        Collections.<Meta>emptyList());

    commitClient.prewriteAsync(request).get(30, TimeUnit.SECONDS);
    commitClient.commitAsync(request).get(30, TimeUnit.SECONDS);

    RegionQueryResult result = scanClient.scanAsync(scanRequest(commitTs,
        rowId)).get(30, TimeUnit.SECONDS);

    assertEquals(1, result.getRows().size());
    assertEquals("raft-grpc-process-smoke",
        result.getRows().get(0).get("payload"));
  }

  /**
   * 停止所有子进程，先写 stop 文件，超时后再强制销毁。
   *
   * @param processes 子进程句柄列表
   */
  private static void stopProcesses(List<ServerProcessHandle> processes) {
    for (ServerProcessHandle handle : processes) {
      try {
        Files.createDirectories(handle.stop.getParent());
        if (!Files.exists(handle.stop)) {
          Files.write(handle.stop, Collections.singletonList("stop"),
              StandardCharsets.UTF_8, StandardOpenOption.CREATE);
        }
      } catch (IOException ignored) {
        // 清理阶段继续处理其他进程，避免一个 stop 文件失败造成子进程泄漏。
      }
    }
    for (ServerProcessHandle handle : processes) {
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
  }

  /**
   * 为异常补充所有子进程日志。
   *
   * @param cause 原始异常
   * @param processes 子进程句柄列表
   * @return 带日志上下文的异常
   */
  private static Exception withProcessLogs(Exception cause,
      List<ServerProcessHandle> processes) {
    AssertionError error = new AssertionError(cause.getMessage()
        + System.lineSeparator() + readLogs(processes), cause);
    return new Exception(error);
  }

  /**
   * 判断所有子进程是否仍存活。
   *
   * @param processes 子进程句柄列表
   * @return 全部存活返回 true，否则返回 false
   */
  private static boolean allAlive(List<ServerProcessHandle> processes) {
    for (ServerProcessHandle handle : processes) {
      if (!handle.process.isAlive()) {
        return false;
      }
    }
    return true;
  }

  /**
   * 读取所有子进程日志用于失败诊断。
   *
   * @param processes 子进程句柄列表
   * @return 拼接后的日志文本
   */
  private static String readLogs(List<ServerProcessHandle> processes) {
    StringBuilder builder = new StringBuilder();
    for (ServerProcessHandle handle : processes) {
      builder.append(readLog(handle));
    }
    return builder.toString();
  }

  /**
   * 读取单个子进程日志。
   *
   * @param handle 子进程句柄
   * @return 日志文本，日志不存在时返回说明
   */
  private static String readLog(ServerProcessHandle handle) {
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

  /**
   * 返回当前 JDK 的 java launcher 路径。
   *
   * @return java 可执行文件路径
   */
  private static String javaExecutable() {
    String name = System.getProperty("os.name", "")
        .toLowerCase().contains("win") ? "java.exe" : "java";
    return new File(new File(System.getProperty("java.home"), "bin"),
        name).getAbsolutePath();
  }

  /**
   * 构造测试用 peer 列表。
   *
   * @param ports 已分配端口
   * @return Raft peer 列表
   */
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

  /**
   * 创建 RaftRClient 使用的 HA2 属性。
   *
   * @param groupId Raft group ID
   * @param peers Raft peer 列表
   * @return client 属性
   */
  private static Properties clientProperties(RaftGroupId groupId,
      List<RaftPeer> peers) {
    Properties properties = new Properties();
    properties.setProperty("HA2.GROUP", groupId.toString());
    properties.setProperty("HA2.NODES", nodes(peers));
    return properties;
  }

  /**
   * 将 peer 列表转换为 HA2.NODES 字符串。
   *
   * @param peers Raft peer 列表
   * @return `node@host:port` 格式的逗号分隔节点串
   */
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

  /**
   * 从 peer 地址中解析端口。
   *
   * @param peer Raft peer
   * @return peer 端口
   */
  private static int portOf(RaftPeer peer) {
    String address = peer.getAddress();
    return Integer.parseInt(address.substring(address.lastIndexOf(':') + 1));
  }

  /**
   * 分配一组当前可用端口。
   *
   * @param count 端口数量
   * @return 端口数组
   * @throws IOException 端口分配失败时抛出
   */
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

  /**
   * 创建 region scan 请求。
   *
   * @param readTs 读取时间戳
   * @param rowId 要读取的 row id
   * @return scan 请求
   */
  private static AdbRegionScanRequest scanRequest(long readTs, long rowId) {
    return new AdbRegionScanRequest(new RegionScanTask("r1",
        new KeyRange(rowKey(rowId).toBytes(), rowKey(rowId + 1).toBytes()),
        Collections.emptyList(), Collections.emptyList(), 0, readTs),
        7, readTs, false, 0);
  }

  /**
   * 创建测试行值。
   *
   * @param txnId 事务 ID
   * @param value 字符串 payload
   * @return ADB RowValue
   */
  private static RowValue rowValue(long txnId, String value) {
    RowValue rowValue = new RowValue();
    rowValue.txnId = txnId;
    rowValue.payload = RowCodec.encode(ValueVarchar.get(value));
    return rowValue;
  }

  /**
   * 创建测试 row key。
   *
   * @param rowId row id
   * @return row key
   */
  private static RowKey rowKey(long rowId) {
    return RowKey.of(TabId.of(1, 0L), rowId);
  }

  /**
   * 子进程句柄，记录 process、同步文件和日志位置。
   */
  private static final class ServerProcessHandle {
    private final String nodeId;
    private final Process process;
    private final Path ready;
    private final Path stop;
    private final StringBuilder logText;
    private final Thread logReader;

    /**
     * 创建子进程句柄。
     *
     * @param nodeId 节点 ID
     * @param process Java 子进程
     * @param ready ready 文件路径
     * @param stop stop 文件路径
     * @param logText 内存日志缓冲
     * @param logReader 日志读取线程
     */
    private ServerProcessHandle(String nodeId, Process process, Path ready,
        Path stop, StringBuilder logText, Thread logReader) {
      this.nodeId = nodeId;
      this.process = process;
      this.ready = ready;
      this.stop = stop;
      this.logText = logText;
      this.logReader = logReader;
    }
  }
}
