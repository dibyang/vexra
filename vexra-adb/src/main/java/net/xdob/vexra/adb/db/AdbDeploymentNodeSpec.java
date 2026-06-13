package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.ha2.AdbRegionNodeMain;

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
   * @param classpath ADB 节点运行 classpath
   * @param mainClass ADB region node main class
   * @param groupId Raft group 标识
   * @param peers Raft peer 列表参数
   * @return 可审计启动命令
   */
  public String startupCommand(String javaCommand, String classpath,
      String mainClass, String groupId, String peers) {
    return argument(javaCommand, "javaCommand")
        + " -Dvexra.adb.nodeId=" + argument(nodeId, "nodeId")
        + " -Dvexra.adb.host=" + argument(host, "host")
        + " -Dvexra.adb.grpcPort=" + grpcPort
        + " -Dvexra.adb.dataDir=" + argument(dataDir, "dataDir")
        + " -Dvexra.adb.role=" + role.name()
        + " -Dvexra.adb.tlsCert=" + argument(tlsCertificatePath,
            "tlsCertificatePath")
        + " -Dvexra.adb.privilegeConfig=" + argument(privilegeConfigPath,
            "privilegeConfigPath")
        + " -cp " + argument(classpath, "classpath")
        + " " + argument(mainClass, "mainClass")
        + " --group " + argument(groupId, "groupId")
        + " --node " + argument(nodeId, "nodeId")
        + " --peers " + argument(peers, "peers")
        + " --host " + argument(host, "host")
        + " --port " + grpcPort
        + " --storage " + argument(childPath(dataDir, "raft"), "storage")
        + " --cache " + argument(childPath(dataDir, "cache"), "cache");
  }

  /**
   * 兼容旧调用方的启动命令生成入口。
   *
   * <p>第二个参数保留旧名称，但现在按 classpath 使用，并生成真实 main class 命令而不是
   * `-jar` 占位命令。单节点 peer 和默认 group 只用于兼容旧测试或外部调用，生产部署应使用带
   * group 和 peers 的重载。</p>
   *
   * @param javaCommand Java 命令
   * @param classpath ADB 节点运行 classpath
   * @return 可审计启动命令
   */
  public String startupCommand(String javaCommand, String classpath) {
    return startupCommand(javaCommand, classpath, AdbRegionNodeMain.MAIN_CLASS,
        AdbDeploymentPlan.DEFAULT_GROUP_ID, nodeId + "@" + endpoint());
  }

  private static String childPath(String parent, String child) {
    String normalized = normalize(parent, "parent");
    if (normalized.endsWith("/") || normalized.endsWith("\\")) {
      return normalized + child;
    }
    return normalized + "/" + child;
  }

  private static String argument(String value, String fieldName) {
    String normalized = normalize(value, fieldName);
    if (normalized.indexOf(' ') < 0 && normalized.indexOf('"') < 0) {
      return normalized;
    }
    return "\"" + normalized.replace("\"", "\\\"") + "\"";
  }

  private static String normalize(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return value.trim();
  }
}
