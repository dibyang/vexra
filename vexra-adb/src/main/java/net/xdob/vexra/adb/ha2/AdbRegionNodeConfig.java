package net.xdob.vexra.adb.ha2;

import net.xdob.vexra.protocol.RaftGroup;
import net.xdob.vexra.protocol.RaftGroupId;
import net.xdob.vexra.protocol.RaftPeer;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ADB region node 的进程启动配置。
 *
 * <p>该对象位于 main 包中，用于把命令行参数解析、必填项校验、peer 解析和当前节点匹配集中到
 * 可单元测试的边界。它只描述本进程所属 Raft group 和本地目录，不直接启动网络服务或写磁盘。</p>
 */
public final class AdbRegionNodeConfig {
  private final RaftGroupId groupId;
  private final String nodeId;
  private final String peersArgument;
  private final String host;
  private final int port;
  private final File storageDir;
  private final File cacheDir;
  private final Path readyFile;
  private final Path stopFile;
  private final List<RaftPeer> peers;
  private final RaftPeer selfPeer;

  /**
   * 创建 ADB region node 启动配置。
   *
   * @param groupId Raft group 标识
   * @param nodeId 当前节点 ID
   * @param peersArgument `node@host:port` 形式的 peer 列表
   * @param host 当前节点 GRPC 监听地址
   * @param port 当前节点 GRPC 监听端口
   * @param storageDir Raft storage 目录
   * @param cacheDir Raft cache 目录
   * @param readyFile 可选 ready 文件路径，允许为空
   * @param stopFile 可选 stop 文件路径，允许为空
   */
  public AdbRegionNodeConfig(RaftGroupId groupId, String nodeId,
      String peersArgument, String host, int port, File storageDir,
      File cacheDir, Path readyFile, Path stopFile) {
    this.groupId = Objects.requireNonNull(groupId, "groupId == null");
    this.nodeId = normalize(nodeId, "node");
    this.peersArgument = normalize(peersArgument, "peers");
    this.host = normalize(host, "host");
    if (port <= 0 || port > 65535) {
      throw new IllegalArgumentException("invalid port: " + port);
    }
    this.port = port;
    this.storageDir = Objects.requireNonNull(storageDir, "storageDir == null");
    this.cacheDir = Objects.requireNonNull(cacheDir, "cacheDir == null");
    this.readyFile = readyFile;
    this.stopFile = stopFile;
    this.peers = Collections.unmodifiableList(
        RaftRClient.parsePeers(this.peersArgument, port));
    this.selfPeer = findPeer(this.peers, this.nodeId);
  }

  /**
   * 从命令行参数创建启动配置。
   *
   * @param args `--key value` 形式的命令行参数
   * @return 经过必填项和 peer 校验的启动配置
   */
  public static AdbRegionNodeConfig parse(String[] args) {
    Map<String, String> values = parseArgs(args);
    return new AdbRegionNodeConfig(
        RaftGroupId.valueOf(require(values, "group")),
        require(values, "node"),
        require(values, "peers"),
        require(values, "host"),
        Integer.parseInt(require(values, "port")),
        new File(require(values, "storage")),
        new File(require(values, "cache")),
        optionalPath(values, "ready"),
        optionalPath(values, "stop"));
  }

  public RaftGroupId getGroupId() {
    return groupId;
  }

  public String getNodeId() {
    return nodeId;
  }

  public String getPeersArgument() {
    return peersArgument;
  }

  public String getHost() {
    return host;
  }

  public int getPort() {
    return port;
  }

  public File getStorageDir() {
    return storageDir;
  }

  public File getCacheDir() {
    return cacheDir;
  }

  public Path getReadyFile() {
    return readyFile;
  }

  public Path getStopFile() {
    return stopFile;
  }

  public List<RaftPeer> getPeers() {
    return peers;
  }

  public RaftPeer getSelfPeer() {
    return selfPeer;
  }

  /**
   * 生成当前节点所属的 Raft group。
   *
   * @return 包含命令行 peer 列表的 Raft group
   */
  public RaftGroup raftGroup() {
    return RaftGroup.valueOf(groupId, peers);
  }

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

  private static String require(Map<String, String> args, String name) {
    String value = args.get(name);
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException("Missing argument: " + name);
    }
    return value.trim();
  }

  private static Path optionalPath(Map<String, String> args, String name) {
    String value = args.get(name);
    if (value == null || value.trim().isEmpty()) {
      return null;
    }
    return Paths.get(value.trim());
  }

  private static String normalize(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return value.trim();
  }

  private static RaftPeer findPeer(List<RaftPeer> peers, String nodeId) {
    for (RaftPeer peer : peers) {
      if (peer.getId().getId().equals(nodeId)) {
        return peer;
      }
    }
    throw new IllegalArgumentException("Unknown node: " + nodeId);
  }
}
