package net.xdob.vexra.adb.db;

import java.util.Objects;

/**
 * ADB 节点上报给控制面的心跳负载。
 *
 * <p>心跳用于刷新节点可用性、部署角色、访问地址和 Raft 进度。当前对象只承载
 * 本地持久化 store 所需的最小字段，后续控制面服务化时可以扩展 region 级统计和
 * 配置版本确认。</p>
 */
public final class AdbNodeHeartbeat {
  private final String nodeId;
  private final AdbDeploymentNodeRole role;
  private final String host;
  private final int port;
  private final long commitIndex;
  private final long appliedIndex;
  private final long heartbeatMillis;
  private final String failureDomain;

  /**
   * 创建节点心跳。
   *
   * @param nodeId 节点唯一标识
   * @param role 部署角色
   * @param host 节点访问主机
   * @param port 节点访问端口
   * @param commitIndex 节点已知 Raft commit index
   * @param appliedIndex 节点已应用 Raft index
   * @param heartbeatMillis 心跳发生时间
   * @param failureDomain 故障域，可为空
   */
  public AdbNodeHeartbeat(String nodeId, AdbDeploymentNodeRole role,
      String host, int port, long commitIndex, long appliedIndex,
      long heartbeatMillis, String failureDomain) {
    this.nodeId = normalize(nodeId, "nodeId");
    this.role = Objects.requireNonNull(role, "role == null");
    this.host = normalize(host, "host");
    this.port = validPort(port);
    this.commitIndex = nonNegative(commitIndex, "commitIndex");
    this.appliedIndex = nonNegative(appliedIndex, "appliedIndex");
    this.heartbeatMillis = nonNegative(heartbeatMillis, "heartbeatMillis");
    this.failureDomain = failureDomain == null ? "" : failureDomain.trim();
  }

  public String getNodeId() {
    return nodeId;
  }

  public AdbDeploymentNodeRole getRole() {
    return role;
  }

  public String getHost() {
    return host;
  }

  public int getPort() {
    return port;
  }

  public long getCommitIndex() {
    return commitIndex;
  }

  public long getAppliedIndex() {
    return appliedIndex;
  }

  public long getHeartbeatMillis() {
    return heartbeatMillis;
  }

  public String getFailureDomain() {
    return failureDomain;
  }

  /**
   * 将健康心跳转换为持久化节点记录。
   *
   * @return 状态为 UP 的控制面节点记录
   */
  public AdbControlPlaneNodeRecord toUpRecord() {
    return new AdbControlPlaneNodeRecord(nodeId, role, host, port,
        AdbControlPlaneNodeStatus.UP, heartbeatMillis, commitIndex,
        appliedIndex, failureDomain);
  }

  private static String normalize(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return value.trim();
  }

  private static int validPort(int port) {
    if (port <= 0 || port > 65535) {
      throw new IllegalArgumentException("invalid port: " + port);
    }
    return port;
  }

  private static long nonNegative(long value, String fieldName) {
    if (value < 0) {
      throw new IllegalArgumentException(fieldName + " is negative: "
          + value);
    }
    return value;
  }
}
