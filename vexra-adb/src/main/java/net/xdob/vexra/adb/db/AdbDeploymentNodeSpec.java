package net.xdob.vexra.adb.db;

import java.util.Objects;

/**
 * ADB 多节点部署中的单节点规格。
 *
 * <p>规格对象用于生成启动命令和做部署预检。它不直接创建进程，也不读取证书文件；
 * 真实部署系统负责把这里的字段映射到 systemd、容器或脚本。</p>
 */
public final class AdbDeploymentNodeSpec {
  private final String nodeId;
  private final String host;
  private final int grpcPort;
  private final String dataDir;
  private final AdbDeploymentNodeRole role;
  private final String tlsCertificatePath;
  private final String privilegeConfigPath;

  /**
   * 创建 ADB 部署节点规格。
   *
   * @param nodeId 节点标识
   * @param host 监听地址或主机名
   * @param grpcPort GRPC 端口
   * @param dataDir 数据目录
   * @param role 部署角色
   * @param tlsCertificatePath TLS 证书路径
   * @param privilegeConfigPath 权限配置路径
   */
  public AdbDeploymentNodeSpec(String nodeId, String host, int grpcPort,
      String dataDir, AdbDeploymentNodeRole role, String tlsCertificatePath,
      String privilegeConfigPath) {
    this.nodeId = normalize(nodeId, "nodeId");
    this.host = normalize(host, "host");
    if (grpcPort <= 0 || grpcPort > 65535) {
      throw new IllegalArgumentException("invalid grpcPort: " + grpcPort);
    }
    this.grpcPort = grpcPort;
    this.dataDir = normalize(dataDir, "dataDir");
    this.role = Objects.requireNonNull(role, "role == null");
    this.tlsCertificatePath = normalize(tlsCertificatePath,
        "tlsCertificatePath");
    this.privilegeConfigPath = normalize(privilegeConfigPath,
        "privilegeConfigPath");
  }

  public String getNodeId() {
    return nodeId;
  }

  public String getHost() {
    return host;
  }

  public int getGrpcPort() {
    return grpcPort;
  }

  public String getDataDir() {
    return dataDir;
  }

  public AdbDeploymentNodeRole getRole() {
    return role;
  }

  public String getTlsCertificatePath() {
    return tlsCertificatePath;
  }

  public String getPrivilegeConfigPath() {
    return privilegeConfigPath;
  }

  /**
   * 返回 host:port 形式的监听端点。
   *
   * @return 节点端点
   */
  public String endpoint() {
    return host + ":" + grpcPort;
  }

  /**
   * 生成该节点的启动命令。
   *
   * @param javaCommand Java 命令
   * @param jarPath ADB 节点启动 jar 路径
   * @return 可审计启动命令
   */
  public String startupCommand(String javaCommand, String jarPath) {
    return normalize(javaCommand, "javaCommand")
        + " -Dvexra.adb.nodeId=" + nodeId
        + " -Dvexra.adb.host=" + host
        + " -Dvexra.adb.grpcPort=" + grpcPort
        + " -Dvexra.adb.dataDir=" + dataDir
        + " -Dvexra.adb.role=" + role.name()
        + " -Dvexra.adb.tlsCert=" + tlsCertificatePath
        + " -Dvexra.adb.privilegeConfig=" + privilegeConfigPath
        + " -jar " + normalize(jarPath, "jarPath");
  }

  private static String normalize(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return value.trim();
  }
}
