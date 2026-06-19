package net.xdob.vexra.adb.db;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * ADB 安全安装模板计划。
 *
 * <p>该计划把 Run-10 生成的集群编排命令和安全默认配置组合成 systemd 与 Windows
 * 服务模板。模板只负责把启动命令、服务用户和安全 JVM 参数落成可审计文本，不直接调用
 * 操作系统安装服务。</p>
 */
public final class AdbSecureInstallPlan {
  private final AdbClusterOrchestrationConfig clusterConfig;
  private final AdbClusterOrchestrationPlan orchestrationPlan;
  private final AdbSecureRuntimeConfig securityConfig;

  public AdbSecureInstallPlan(AdbClusterOrchestrationConfig clusterConfig,
      AdbSecureRuntimeConfig securityConfig) {
    this.clusterConfig = Objects.requireNonNull(clusterConfig,
        "clusterConfig == null");
    this.orchestrationPlan = clusterConfig.toPlan();
    this.securityConfig = Objects.requireNonNull(securityConfig,
        "securityConfig == null");
    securityConfig.toRuntimeOptions();
  }

  /**
   * 生成 SQL server 的 systemd unit 模板。
   *
   * @return systemd unit 文本
   */
  public String sqlSystemdUnit() {
    return systemdUnit("vexra-adb-sql", "ADB_SQL_SERVER_OPTS",
        securityConfig.jvmOptions("sql"), orchestrationPlan.sqlServerCommand());
  }

  /**
   * 生成 region node 的 systemd unit 模板。
   *
   * @return systemd unit 文本列表
   */
  public List<String> regionSystemdUnits() {
    List<String> units = new ArrayList<>();
    List<String> commands = orchestrationPlan.regionNodeCommands();
    List<AdbDeploymentNodeSpec> nodes = clusterConfig.getNodes();
    for (int i = 0; i < nodes.size(); i++) {
      AdbDeploymentNodeSpec node = nodes.get(i);
      units.add(systemdUnit("vexra-adb-region-" + node.getNodeId(),
          "ADB_REGION_NODE_OPTS", securityConfig.jvmOptions(
              node.getNodeId()), commands.get(i)));
    }
    return Collections.unmodifiableList(units);
  }

  /**
   * 生成 Windows sc.exe 安装命令。
   *
   * @return Windows 服务安装命令
   */
  public List<String> windowsServiceCommands() {
    List<String> commands = new ArrayList<>();
    commands.add(windowsService("vexra-adb-sql",
        "set ADB_SQL_SERVER_OPTS=" + securityConfig.jvmOptions("sql")
            + " && " + orchestrationPlan.sqlServerCommand()));
    List<String> regionCommands = orchestrationPlan.regionNodeCommands();
    List<AdbDeploymentNodeSpec> nodes = clusterConfig.getNodes();
    for (int i = 0; i < nodes.size(); i++) {
      AdbDeploymentNodeSpec node = nodes.get(i);
      commands.add(windowsService("vexra-adb-region-" + node.getNodeId(),
          "set ADB_REGION_NODE_OPTS="
              + securityConfig.jvmOptions(node.getNodeId()) + " && "
              + regionCommands.get(i)));
    }
    return Collections.unmodifiableList(commands);
  }

  private String systemdUnit(String name, String envName, String envValue,
      String command) {
    return "[Unit]\n"
        + "Description=" + name + "\n"
        + "After=network-online.target\n\n"
        + "[Service]\n"
        + "User=" + securityConfig.getServiceUser() + "\n"
        + "Environment=\"" + envName + "=" + envValue + "\"\n"
        + "ExecStart=" + command + "\n"
        + "Restart=on-failure\n"
        + "NoNewPrivileges=true\n"
        + "PrivateTmp=true\n\n"
        + "[Install]\n"
        + "WantedBy=multi-user.target\n";
  }

  private static String windowsService(String name, String command) {
    return "sc.exe create " + name + " binPath= \"cmd.exe /c " + command
        + "\" start= demand";
  }
}
