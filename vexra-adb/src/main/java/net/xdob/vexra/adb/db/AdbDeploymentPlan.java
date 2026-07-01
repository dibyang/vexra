package net.xdob.vexra.adb.db;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * ADB 多节点部署计划。
 *
 * <p>计划对象把分布式安全开关、节点规格和启动命令生成放到一个可测试边界。
 * 它不直接启动进程，真实部署系统可以把 {@link #startupCommands()} 的输出写入脚本、
 * 容器编排或服务管理器。</p>
 */
public final class AdbDeploymentPlan {
  /** 兼容旧部署计划构造器时使用的默认 ADB Raft group 标识。 */
  public static final String DEFAULT_GROUP_ID = "adb-default-region";
  /** ADB Raft region node 默认 main class 名称。 */
  public static final String DEFAULT_REGION_NODE_MAIN_CLASS =
      "net.xdob.vexra.adb.ha2.AdbRegionNodeMain";
  private final AdbDistributedRuntimeOptions runtimeOptions;
  private final String javaCommand;
  private final String classpath;
  private final String mainClass;
  private final String groupId;
  private final List<AdbDeploymentNodeSpec> nodes;

  /**
   * 创建 ADB 部署计划。
   *
   * @param runtimeOptions 分布式运行时安全选项
   * @param javaCommand Java 命令
   * @param jarPath 节点启动 jar 路径
   * @param nodes 节点规格列表
   */
  public AdbDeploymentPlan(AdbDistributedRuntimeOptions runtimeOptions,
      String javaCommand, String jarPath, List<AdbDeploymentNodeSpec> nodes) {
    this(runtimeOptions, javaCommand, jarPath, DEFAULT_REGION_NODE_MAIN_CLASS,
        DEFAULT_GROUP_ID, nodes);
  }

  /**
   * 创建带 Raft group 标识的 ADB 部署计划。
   *
   * @param runtimeOptions 分布式运行时安全选项
   * @param javaCommand Java 命令
   * @param classpath 节点运行 classpath
   * @param groupId Raft group 标识
   * @param nodes 节点规格列表
   */
  public AdbDeploymentPlan(AdbDistributedRuntimeOptions runtimeOptions,
      String javaCommand, String classpath, String groupId,
      List<AdbDeploymentNodeSpec> nodes) {
    this(runtimeOptions, javaCommand, classpath, DEFAULT_REGION_NODE_MAIN_CLASS,
        groupId, nodes);
  }

  /**
   * 创建完整指定 main class 的 ADB 部署计划。
   *
   * @param runtimeOptions 分布式运行时安全选项
   * @param javaCommand Java 命令
   * @param classpath 节点运行 classpath
   * @param mainClass ADB region node main class
   * @param groupId Raft group 标识
   * @param nodes 节点规格列表
   */
  public AdbDeploymentPlan(AdbDistributedRuntimeOptions runtimeOptions,
      String javaCommand, String classpath, String mainClass, String groupId,
      List<AdbDeploymentNodeSpec> nodes) {
    this.runtimeOptions = Objects.requireNonNull(runtimeOptions,
        "runtimeOptions == null");
    this.javaCommand = normalize(javaCommand, "javaCommand");
    this.classpath = normalize(classpath, "classpath");
    this.mainClass = normalize(mainClass, "mainClass");
    this.groupId = normalize(groupId, "groupId");
    this.nodes = immutableNodes(nodes);
    validate();
  }

  public AdbDistributedRuntimeOptions getRuntimeOptions() {
    return runtimeOptions;
  }

  public String getJavaCommand() {
    return javaCommand;
  }

  public String getClasspath() {
    return classpath;
  }

  public String getMainClass() {
    return mainClass;
  }

  public String getGroupId() {
    return groupId;
  }

  /**
   * 返回兼容旧调用方的 classpath 值。
   *
   * @return 节点运行 classpath
   */
  public String getJarPath() {
    return classpath;
  }

  public List<AdbDeploymentNodeSpec> getNodes() {
    return nodes;
  }

  /**
   * 生成所有节点的启动命令清单。
   *
   * @return 按节点顺序排列的启动命令
   */
  public List<String> startupCommands() {
    List<String> commands = new ArrayList<>();
    String peers = peersArgument();
    for (AdbDeploymentNodeSpec node : nodes) {
      commands.add(node.startupCommand(javaCommand, classpath, mainClass,
          groupId, peers));
    }
    return Collections.unmodifiableList(commands);
  }

  /**
   * 生成 `node@host:port` 格式的 Raft peer 参数。
   *
   * @return 按部署计划节点顺序排列的 peer 参数
   */
  public String peersArgument() {
    StringBuilder builder = new StringBuilder();
    for (AdbDeploymentNodeSpec node : nodes) {
      if (builder.length() > 0) {
        builder.append(',');
      }
      builder.append(node.getNodeId()).append('@').append(node.endpoint());
    }
    return builder.toString();
  }

  private void validate() {
    Set<String> nodeIds = new HashSet<>();
    Set<String> endpoints = new HashSet<>();
    Set<String> dataDirs = new HashSet<>();
    int dataNodes = 0;
    int witnessNodes = 0;
    for (AdbDeploymentNodeSpec node : nodes) {
      if (!nodeIds.add(node.getNodeId())) {
        throw new IllegalArgumentException("duplicate nodeId: "
            + node.getNodeId());
      }
      if (!endpoints.add(node.endpoint())) {
        throw new IllegalArgumentException("duplicate endpoint: "
            + node.endpoint());
      }
      if (!dataDirs.add(node.getDataDir())) {
        throw new IllegalArgumentException("duplicate dataDir: "
            + node.getDataDir());
      }
      if (node.getRole() == AdbDeploymentNodeRole.DATA_NODE) {
        dataNodes++;
      } else if (node.getRole() == AdbDeploymentNodeRole.WITNESS_NODE) {
        witnessNodes++;
      }
    }
    if (runtimeOptions.isDistributedEnabled()
        && (dataNodes < 2 || witnessNodes < 1)) {
      throw new IllegalArgumentException(
          "distributed deployment requires at least 2 data nodes and 1 witness");
    }
  }

  private static List<AdbDeploymentNodeSpec> immutableNodes(
      List<AdbDeploymentNodeSpec> nodes) {
    Objects.requireNonNull(nodes, "nodes == null");
    if (nodes.isEmpty()) {
      throw new IllegalArgumentException("nodes is empty");
    }
    List<AdbDeploymentNodeSpec> copy = new ArrayList<>();
    for (AdbDeploymentNodeSpec node : nodes) {
      copy.add(Objects.requireNonNull(node, "node is null"));
    }
    return Collections.unmodifiableList(copy);
  }

  private static String normalize(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return value.trim();
  }
}
