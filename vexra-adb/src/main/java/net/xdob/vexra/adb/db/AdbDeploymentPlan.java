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
  private final AdbDistributedRuntimeOptions runtimeOptions;
  private final String javaCommand;
  private final String jarPath;
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
    this.runtimeOptions = Objects.requireNonNull(runtimeOptions,
        "runtimeOptions == null");
    this.javaCommand = normalize(javaCommand, "javaCommand");
    this.jarPath = normalize(jarPath, "jarPath");
    this.nodes = immutableNodes(nodes);
    validate();
  }

  public AdbDistributedRuntimeOptions getRuntimeOptions() {
    return runtimeOptions;
  }

  public String getJavaCommand() {
    return javaCommand;
  }

  public String getJarPath() {
    return jarPath;
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
    for (AdbDeploymentNodeSpec node : nodes) {
      commands.add(node.startupCommand(javaCommand, jarPath));
    }
    return Collections.unmodifiableList(commands);
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
