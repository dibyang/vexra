package net.xdob.vexra.adb.db;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/**
 * ADB runtime 集群编排配置。
 *
 * <p>该配置使用 UTF-8 properties 描述 SQL server、region node、runtime 目录和共享
 * catalog。它不直接启动进程，只把“一份集群配置”解析为可审计的编排计划，供启动脚本、
 * 服务管理器或测试环境执行。</p>
 */
public final class AdbClusterOrchestrationConfig {
  private final String runtimeDir;
  private final String groupId;
  private final int sqlPort;
  private final String sqlBaseDir;
  private final boolean sqlIfNotExists;
  private final String sqlReadyFile;
  private final String sqlStopFile;
  private final String catalogPath;
  private final List<AdbDeploymentNodeSpec> nodes;
  private final Properties catalogProperties;

  private AdbClusterOrchestrationConfig(String runtimeDir, String groupId,
      int sqlPort, String sqlBaseDir, boolean sqlIfNotExists,
      String sqlReadyFile, String sqlStopFile, String catalogPath,
      List<AdbDeploymentNodeSpec> nodes, Properties catalogProperties) {
    this.runtimeDir = requireText(runtimeDir, "runtimeDir");
    this.groupId = requireText(groupId, "groupId");
    if (sqlPort <= 0 || sqlPort > 65535) {
      throw new IllegalArgumentException("invalid sqlPort: " + sqlPort);
    }
    this.sqlPort = sqlPort;
    this.sqlBaseDir = requireText(sqlBaseDir, "sqlBaseDir");
    this.sqlIfNotExists = sqlIfNotExists;
    this.sqlReadyFile = trimToNull(sqlReadyFile);
    this.sqlStopFile = trimToNull(sqlStopFile);
    this.catalogPath = requireText(catalogPath, "catalogPath");
    this.nodes = immutableNodes(nodes);
    this.catalogProperties = copyOf(catalogProperties);
    validate();
  }

  /**
   * 从 UTF-8 properties 文件加载集群编排配置。
   *
   * @param path 配置文件路径
   * @return 集群编排配置
   */
  public static AdbClusterOrchestrationConfig load(Path path) {
    Objects.requireNonNull(path, "path == null");
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(path,
        StandardCharsets.UTF_8)) {
      properties.load(reader);
    } catch (IOException e) {
      throw new IllegalArgumentException("failed to load cluster config: "
          + path, e);
    }
    return fromProperties(properties);
  }

  /**
   * 从 properties 对象创建集群编排配置。
   *
   * @param properties 配置项集合
   * @return 集群编排配置
   */
  public static AdbClusterOrchestrationConfig fromProperties(
      Properties properties) {
    Objects.requireNonNull(properties, "properties == null");
    List<AdbDeploymentNodeSpec> nodes = parseNodes(properties);
    Properties catalog = catalogProperties(properties);
    return new AdbClusterOrchestrationConfig(
        require(properties, "adb.cluster.runtimeDir"),
        require(properties, "adb.cluster.group"),
        Integer.parseInt(require(properties, "adb.cluster.sql.port")),
        require(properties, "adb.cluster.sql.baseDir"),
        bool(properties.getProperty("adb.cluster.sql.ifNotExists"), true),
        properties.getProperty("adb.cluster.sql.ready"),
        properties.getProperty("adb.cluster.sql.stop"),
        require(properties, "adb.cluster.catalog.path"),
        nodes, catalog);
  }

  /**
   * 创建可执行编排计划。
   *
   * @return 编排计划
   */
  public AdbClusterOrchestrationPlan toPlan() {
    return new AdbClusterOrchestrationPlan(this);
  }

  public String getRuntimeDir() {
    return runtimeDir;
  }

  public String getGroupId() {
    return groupId;
  }

  public int getSqlPort() {
    return sqlPort;
  }

  public String getSqlBaseDir() {
    return sqlBaseDir;
  }

  public boolean isSqlIfNotExists() {
    return sqlIfNotExists;
  }

  public String getSqlReadyFile() {
    return sqlReadyFile;
  }

  public String getSqlStopFile() {
    return sqlStopFile;
  }

  public String getCatalogPath() {
    return catalogPath;
  }

  public List<AdbDeploymentNodeSpec> getNodes() {
    return nodes;
  }

  public Properties getCatalogProperties() {
    return copyOf(catalogProperties);
  }

  private void validate() {
    Set<String> endpoints = new HashSet<>();
    Set<String> dataDirs = new HashSet<>();
    for (AdbDeploymentNodeSpec node : nodes) {
      if (!endpoints.add(node.endpoint())) {
        throw new IllegalArgumentException("duplicate endpoint: "
            + node.endpoint());
      }
      if (node.getGrpcPort() == sqlPort && node.getHost().equals("127.0.0.1")) {
        throw new IllegalArgumentException("sql port conflicts with node: "
            + node.getNodeId());
      }
      if (!dataDirs.add(node.getDataDir())) {
        throw new IllegalArgumentException("duplicate dataDir: "
            + node.getDataDir());
      }
    }
  }

  private static List<AdbDeploymentNodeSpec> parseNodes(Properties properties) {
    String[] nodeIds = require(properties, "adb.cluster.nodes").split(",");
    List<AdbDeploymentNodeSpec> nodes = new ArrayList<>();
    for (String rawNodeId : nodeIds) {
      String nodeId = requireText(rawNodeId, "nodeId");
      String prefix = "adb.cluster.node." + nodeId + ".";
      nodes.add(new AdbDeploymentNodeSpec(nodeId,
          require(properties, prefix + "host"),
          Integer.parseInt(require(properties, prefix + "port")),
          require(properties, prefix + "dataDir"),
          AdbDeploymentNodeRole.valueOf(require(properties, prefix + "role")),
          optional(properties, prefix + "tlsCert", "conf/" + nodeId
              + ".pem"),
          optional(properties, prefix + "privilegeConfig", "conf/" + nodeId
              + "-privileges.json")));
    }
    return nodes;
  }

  private static Properties catalogProperties(Properties source) {
    Properties catalog = new Properties();
    for (String name : source.stringPropertyNames()) {
      if (name.startsWith("adb.catalog.")) {
        catalog.setProperty(name, source.getProperty(name));
      }
    }
    if (!catalog.containsKey("adb.catalog.raft.group")) {
      catalog.setProperty("adb.catalog.raft.group",
          require(source, "adb.cluster.group"));
    }
    if (!catalog.containsKey("adb.catalog.raft.peers")) {
      catalog.setProperty("adb.catalog.raft.peers", peers(parseNodes(source)));
    }
    if (!catalog.containsKey("adb.catalog.raft.dbName")) {
      catalog.setProperty("adb.catalog.raft.dbName", "adb");
    }
    if (!catalog.containsKey("adb.catalog.tso.readTs")
        && !catalog.containsKey("adb.catalog.tso.current")) {
      throw new IllegalArgumentException(
          "adb.catalog.tso.readTs or adb.catalog.tso.current is required");
    }
    return catalog;
  }

  static String peers(List<AdbDeploymentNodeSpec> nodes) {
    StringBuilder builder = new StringBuilder();
    for (AdbDeploymentNodeSpec node : nodes) {
      if (builder.length() > 0) {
        builder.append(',');
      }
      builder.append(node.getNodeId()).append('@').append(node.endpoint());
    }
    return builder.toString();
  }

  private static List<AdbDeploymentNodeSpec> immutableNodes(
      List<AdbDeploymentNodeSpec> nodes) {
    Objects.requireNonNull(nodes, "nodes == null");
    if (nodes.isEmpty()) {
      throw new IllegalArgumentException("nodes is empty");
    }
    return Collections.unmodifiableList(new ArrayList<>(nodes));
  }

  private static String require(Properties properties, String key) {
    return requireText(properties.getProperty(key), key);
  }

  private static String optional(Properties properties, String key,
      String defaultValue) {
    String value = trimToNull(properties.getProperty(key));
    return value == null ? defaultValue : value;
  }

  private static boolean bool(String value, boolean defaultValue) {
    String trimmed = trimToNull(value);
    return trimmed == null ? defaultValue : Boolean.parseBoolean(trimmed);
  }

  private static Properties copyOf(Properties source) {
    Properties copy = new Properties();
    copy.putAll(source);
    return copy;
  }

  private static String requireText(String value, String name) {
    String text = trimToNull(value);
    if (text == null) {
      throw new IllegalArgumentException(name + " is required");
    }
    return text;
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
