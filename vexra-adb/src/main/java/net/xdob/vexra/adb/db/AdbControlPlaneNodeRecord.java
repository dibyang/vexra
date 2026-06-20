package net.xdob.vexra.adb.db;

import java.util.Objects;

/**
 * ADB 控制面持久化的节点记录。
 *
 * <p>该对象是 `adb_cp_node` 的本地值对象形式，记录部署角色、访问地址、最近心跳、
 * Raft 进度和控制面状态。它只描述控制面事实，不直接打开网络连接，也不替代
 * region 内部的 replica role。</p>
 */
public final class AdbControlPlaneNodeRecord {
  private final String nodeId;
  private final AdbDeploymentNodeRole role;
  private final String host;
  private final int port;
  private final AdbControlPlaneNodeStatus status;
  private final long lastHeartbeatMillis;
  private final long commitIndex;
  private final long appliedIndex;
  private final String failureDomain;

  /**
   * 创建控制面节点记录。
   *
   * @param nodeId 节点唯一标识
   * @param role 部署角色
   * @param host 节点访问主机
   * @param port 节点访问端口
   * @param status 控制面节点状态
   * @param lastHeartbeatMillis 最近心跳时间戳
   * @param commitIndex 节点已知 Raft commit index
   * @param appliedIndex 节点已应用 Raft index
   * @param failureDomain 故障域，可为空
   */
  public AdbControlPlaneNodeRecord(String nodeId,
      AdbDeploymentNodeRole role, String host, int port,
      AdbControlPlaneNodeStatus status, long lastHeartbeatMillis,
      long commitIndex, long appliedIndex, String failureDomain) {
    this.nodeId = normalize(nodeId, "nodeId");
    this.role = Objects.requireNonNull(role, "role == null");
    this.host = normalize(host, "host");
    this.port = validPort(port);
    this.status = Objects.requireNonNull(status, "status == null");
    this.lastHeartbeatMillis = nonNegative(lastHeartbeatMillis,
        "lastHeartbeatMillis");
    this.commitIndex = nonNegative(commitIndex, "commitIndex");
    this.appliedIndex = nonNegative(appliedIndex, "appliedIndex");
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

  public AdbControlPlaneNodeStatus getStatus() {
    return status;
  }

  public long getLastHeartbeatMillis() {
    return lastHeartbeatMillis;
  }

  public long getCommitIndex() {
    return commitIndex;
  }

  public long getAppliedIndex() {
    return appliedIndex;
  }

  public String getFailureDomain() {
    return failureDomain;
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
