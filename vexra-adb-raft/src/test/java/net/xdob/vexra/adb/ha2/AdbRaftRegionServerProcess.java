package net.xdob.vexra.adb.ha2;

import net.xdob.vexra.RaftConfigKeys;
import net.xdob.vexra.adb.AdbStateMachine;
import net.xdob.vexra.conf.Parameters;
import net.xdob.vexra.conf.RaftProperties;
import net.xdob.vexra.grpc.GrpcConfigKeys;
import net.xdob.vexra.protocol.RaftGroup;
import net.xdob.vexra.protocol.RaftGroupId;
import net.xdob.vexra.protocol.RaftPeer;
import net.xdob.vexra.rpc.SupportedRpcType;
import net.xdob.vexra.server.RaftServer;
import net.xdob.vexra.server.config.RaftServerConfigKeys;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ADB Raft region 测试子进程入口。
 *
 * <p>该类只用于测试级 OS 多进程 smoke。每个 JVM 进程启动一个真实
 * {@link RaftServer}，通过 ready 文件通知父进程启动完成，并在 stop 文件出现后
 * 关闭服务。它不拥有生产部署配置、服务发现或安全策略。</p>
 */
public final class AdbRaftRegionServerProcess {
  private AdbRaftRegionServerProcess() {
  }

  /**
   * 启动一个测试用 ADB Raft region server 进程。
   *
   * @param args 命令行参数，使用 `--name value` 格式传入 group、node、peers、
   *             port、storage、cache、ready 和 stop
   */
  public static void main(String[] args) {
    try {
      run(parseArgs(args));
    } catch (Throwable t) {
      t.printStackTrace(System.err);
      System.err.flush();
      System.exit(1);
    }
  }

  /**
   * 根据命令行参数创建并保持测试 server 存活。
   *
   * @param args 已解析的启动参数
   * @throws Exception server 启动、ready 文件写入或等待过程中出现失败
   */
  private static void run(Map<String, String> args) throws Exception {
    String nodeId = require(args, "node");
    int port = Integer.parseInt(require(args, "port"));
    Path ready = Paths.get(require(args, "ready"));
    Path stop = Paths.get(require(args, "stop"));
    RaftGroupId groupId = RaftGroupId.valueOf(require(args, "group"));
    List<RaftPeer> peers = RaftRClient.parsePeers(require(args, "peers"), port);
    RaftPeer self = findPeer(peers, nodeId);
    RaftGroup group = RaftGroup.valueOf(groupId, peers);

    RaftServer server = newServer(group, self, port,
        new File(require(args, "storage")), new File(require(args, "cache")));
    try {
      server.start();
      writeReadyFile(ready, nodeId);
      waitForStopFile(stop);
    } finally {
      server.close();
    }
  }

  /**
   * 创建单节点 RaftServer，配置独立 GRPC 端口、Raft 存储和 ADB 状态机。
   *
   * @param group 当前测试 Raft group
   * @param peer 当前节点 peer
   * @param port 当前节点 GRPC 端口
   * @param storageDir 当前节点 Raft storage 目录
   * @param cacheDir 当前节点 cache 目录
   * @return 未启动的 RaftServer
   * @throws IOException server 构建失败时抛出
   */
  private static RaftServer newServer(RaftGroup group, RaftPeer peer, int port,
      File storageDir, File cacheDir) throws IOException {
    RaftProperties properties = new RaftProperties();
    RaftConfigKeys.Rpc.setType(properties, SupportedRpcType.GRPC);
    GrpcConfigKeys.Server.setHost(properties, "127.0.0.1");
    GrpcConfigKeys.Server.setPort(properties, port);
    RaftServerConfigKeys.setStorageDir(properties,
        Collections.singletonList(storageDir));
    RaftServerConfigKeys.setCacheDir(properties, cacheDir);

    return RaftServer.newBuilder()
        .setServerId(peer.getId())
        .setGroup(group)
        .setProperties(properties)
        .setParameters(new Parameters())
        .setStateMachineRegistry(gid -> new AdbStateMachine(gid,
            peer.getId()))
        .build();
  }

  /**
   * 解析 `--key value` 形式的启动参数。
   *
   * @param args 原始命令行参数
   * @return 参数名到参数值的映射
   */
  private static Map<String, String> parseArgs(String[] args) {
    Map<String, String> values = new HashMap<>();
    for (int i = 0; i < args.length; i += 2) {
      if (i + 1 >= args.length || !args[i].startsWith("--")) {
        throw new IllegalArgumentException("Illegal argument at index " + i);
      }
      values.put(args[i].substring(2), args[i + 1]);
    }
    return values;
  }

  /**
   * 读取必填参数。
   *
   * @param args 参数映射
   * @param name 参数名
   * @return 参数值
   */
  private static String require(Map<String, String> args, String name) {
    String value = args.get(name);
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException("Missing argument: " + name);
    }
    return value;
  }

  /**
   * 在 peer 列表中查找当前节点。
   *
   * @param peers 当前 Raft group 的 peer 列表
   * @param nodeId 当前节点 ID
   * @return 与 nodeId 匹配的 peer
   */
  private static RaftPeer findPeer(List<RaftPeer> peers, String nodeId) {
    for (RaftPeer peer : peers) {
      if (peer.getId().getId().equals(nodeId)) {
        return peer;
      }
    }
    throw new IllegalArgumentException("Unknown node: " + nodeId);
  }

  /**
   * 写入 ready 文件，通知父进程当前 server 已完成启动调用。
   *
   * @param ready ready 文件路径
   * @param nodeId 当前节点 ID
   * @throws IOException ready 文件写入失败时抛出
   */
  private static void writeReadyFile(Path ready, String nodeId)
      throws IOException {
    Files.createDirectories(ready.getParent());
    Files.write(ready, Collections.singletonList("ready " + nodeId),
        StandardCharsets.UTF_8, StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING);
  }

  /**
   * 等待父进程写入 stop 文件后退出。
   *
   * @param stop stop 文件路径
   * @throws InterruptedException 等待过程中被中断时抛出
   */
  private static void waitForStopFile(Path stop) throws InterruptedException {
    while (!Files.exists(stop)) {
      Thread.sleep(200L);
    }
  }
}
