package net.xdob.vexra.adb.db;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * ADB 集群自动编排计划。
 *
 * <p>计划对象把一份集群配置展开为 SQL server 命令、region node 命令和共享 catalog
 * 文件内容。它只负责生成可审计产物，不在当前 JVM 内直接拉起子进程，避免测试和部署系统
 * 的生命周期相互缠绕。</p>
 */
public final class AdbClusterOrchestrationPlan {
  private final AdbClusterOrchestrationConfig config;

  AdbClusterOrchestrationPlan(AdbClusterOrchestrationConfig config) {
    this.config = Objects.requireNonNull(config, "config == null");
  }

  /**
   * 生成 SQL server 启动命令。
   *
   * @return SQL server 启动命令
   */
  public String sqlServerCommand() {
    StringBuilder builder = new StringBuilder()
        .append(script("adb-sql-server"))
        .append(" --port ").append(config.getSqlPort())
        .append(" --baseDir ").append(arg(config.getSqlBaseDir()))
        .append(" --ifNotExists ").append(config.isSqlIfNotExists());
    if (config.getSqlReadyFile() != null) {
      builder.append(" --ready ").append(arg(config.getSqlReadyFile()));
    }
    if (config.getSqlStopFile() != null) {
      builder.append(" --stop ").append(arg(config.getSqlStopFile()));
    }
    return builder.toString();
  }

  /**
   * 生成所有 region node 启动命令。
   *
   * @return region node 启动命令列表
   */
  public List<String> regionNodeCommands() {
    List<String> commands = new ArrayList<>();
    String peers = AdbClusterOrchestrationConfig.peers(config.getNodes());
    for (AdbDeploymentNodeSpec node : config.getNodes()) {
      commands.add(script("adb-region-node")
          + " --group " + arg(config.getGroupId())
          + " --node " + arg(node.getNodeId())
          + " --peers " + arg(peers)
          + " --host " + arg(node.getHost())
          + " --port " + node.getGrpcPort()
          + " --storage " + arg(childPath(node.getDataDir(), "raft"))
          + " --cache " + arg(childPath(node.getDataDir(), "cache"))
          + " --ready " + arg(childPath(node.getDataDir(), "run/ready"))
          + " --stop " + arg(childPath(node.getDataDir(), "run/stop")));
    }
    return Collections.unmodifiableList(commands);
  }

  /**
   * 返回共享 catalog 文件路径。
   *
   * @return catalog 文件路径
   */
  public String catalogPath() {
    return config.getCatalogPath();
  }

  /**
   * 写出 SQL server 使用的共享 catalog 文件。
   *
   * @return catalog 文件路径
   * @throws IOException 写文件失败时抛出
   */
  public Path writeCatalog() throws IOException {
    Path path = Paths.get(config.getCatalogPath());
    Path parent = path.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    try (Writer writer = Files.newBufferedWriter(path,
        StandardCharsets.UTF_8)) {
      catalogProperties().store(writer, "ADB shared catalog");
    }
    return path;
  }

  /**
   * 返回共享 catalog 内容。
   *
   * @return catalog properties
   */
  public Properties catalogProperties() {
    return config.getCatalogProperties();
  }

  /**
   * 执行轻量预检并返回诊断文本。
   *
   * @return 预检诊断
   */
  public List<String> preflightChecks() {
    List<String> checks = new ArrayList<>();
    checks.add("runtimeDir=" + config.getRuntimeDir());
    checks.add("sqlPort=" + config.getSqlPort());
    checks.add("regionNodes=" + config.getNodes().size());
    checks.add("peers=" + AdbClusterOrchestrationConfig.peers(
        config.getNodes()));
    checks.add("catalogPath=" + config.getCatalogPath());
    return Collections.unmodifiableList(checks);
  }

  /**
   * 生成完整的人类可读编排计划。
   *
   * @return 编排计划文本
   */
  public String render() {
    StringBuilder builder = new StringBuilder();
    builder.append("[preflight]\n");
    for (String check : preflightChecks()) {
      builder.append(check).append('\n');
    }
    builder.append("[catalog]\n");
    for (String key : catalogProperties().stringPropertyNames()) {
      builder.append(key).append('=')
          .append(catalogProperties().getProperty(key)).append('\n');
    }
    builder.append("[sql]\n").append(sqlServerCommand()).append('\n');
    builder.append("[region]\n");
    for (String command : regionNodeCommands()) {
      builder.append(command).append('\n');
    }
    return builder.toString();
  }

  private String script(String name) {
    return arg(childPath(childPath(config.getRuntimeDir(), "bin"),
        name + ".bat"));
  }

  private static String childPath(String parent, String child) {
    String normalized = parent.replace('\\', '/');
    return normalized.endsWith("/") ? normalized + child : normalized + "/"
        + child;
  }

  private static String arg(String value) {
    if (value.indexOf(' ') < 0 && value.indexOf('"') < 0) {
      return value;
    }
    return "\"" + value.replace("\"", "\\\"") + "\"";
  }
}
