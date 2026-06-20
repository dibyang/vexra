package net.xdob.vexra.adb.db;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * ADB 集群配置预检器。
 *
 * <p>该预检器面向 GA-05 的“启动前失败”边界：生产集群必须明确使用
 * 2 data + 1 witness 拓扑，必须开启 TLS 与认证，并且 runtime 脚本和节点目录不能
 * 明显不可用。它不尝试 bind 端口或启动进程，避免预检产生副作用。</p>
 */
public final class AdbClusterPreflightChecker {
  private final AdbClusterOrchestrationConfig config;
  private final Properties properties;
  private final boolean strictFiles;
  private final boolean checkRuntimeScripts;

  /**
   * 创建集群预检器。
   *
   * @param config 已解析的集群编排配置
   * @param properties 原始配置项，用于读取安全开关
   * @param strictFiles 是否要求 TLS/权限文件已经存在
   * @param checkRuntimeScripts 是否检查 runtime bin 脚本
   */
  public AdbClusterPreflightChecker(AdbClusterOrchestrationConfig config,
      Properties properties, boolean strictFiles,
      boolean checkRuntimeScripts) {
    this.config = Objects.requireNonNull(config, "config == null");
    this.properties = Objects.requireNonNull(properties,
        "properties == null");
    this.strictFiles = strictFiles;
    this.checkRuntimeScripts = checkRuntimeScripts;
  }

  /**
   * 执行集群预检。
   *
   * @return 预检报告
   */
  public AdbClusterPreflightReport check() {
    List<String> passed = new ArrayList<>();
    List<String> failed = new ArrayList<>();
    checkSecurity(passed, failed);
    checkTopology(passed, failed);
    checkRuntimeScripts(passed, failed);
    checkNodeFilesAndDirs(passed, failed);
    checkCatalog(passed, failed);
    return new AdbClusterPreflightReport(passed, failed);
  }

  private void checkSecurity(List<String> passed, List<String> failed) {
    boolean tls = Boolean.parseBoolean(properties.getProperty(
        "adb.security.tls.enabled", "false"));
    boolean auth = Boolean.parseBoolean(properties.getProperty(
        "adb.security.auth.enabled", "false"));
    record(tls, "tls.enabled=true", passed, failed);
    record(auth, "auth.enabled=true", passed, failed);
  }

  private void checkTopology(List<String> passed, List<String> failed) {
    int dataNodes = 0;
    int witnessNodes = 0;
    for (AdbDeploymentNodeSpec node : config.getNodes()) {
      if (node.getRole() == AdbDeploymentNodeRole.DATA_NODE) {
        dataNodes++;
      } else if (node.getRole() == AdbDeploymentNodeRole.WITNESS_NODE) {
        witnessNodes++;
      }
    }
    record(dataNodes == 2 && witnessNodes == 1,
        "topology=2data1witness dataNodes=" + dataNodes
            + ", witnessNodes=" + witnessNodes, passed, failed);
  }

  private void checkRuntimeScripts(List<String> passed, List<String> failed) {
    if (!checkRuntimeScripts) {
      passed.add("runtimeScripts=skipped");
      return;
    }
    Path binDir = Paths.get(config.getRuntimeDir(), "bin");
    record(Files.isDirectory(binDir), "runtime.bin.exists=" + binDir,
        passed, failed);
    record(hasScript(binDir, "adb-sql-server"),
        "runtime.script=adb-sql-server", passed, failed);
    record(hasScript(binDir, "adb-region-node"),
        "runtime.script=adb-region-node", passed, failed);
  }

  private void checkNodeFilesAndDirs(List<String> passed, List<String> failed) {
    for (AdbDeploymentNodeSpec node : config.getNodes()) {
      Path dataDir = Paths.get(node.getDataDir());
      record(!Files.exists(dataDir) || Files.isDirectory(dataDir),
          "node." + node.getNodeId() + ".dataDir.usable=" + dataDir,
          passed, failed);
      Path parent = dataDir.toAbsolutePath().getParent();
      record(parent == null || !Files.exists(parent) || Files.isDirectory(parent),
          "node." + node.getNodeId() + ".dataDir.parent=" + parent,
          passed, failed);
      checkFile(node.getTlsCertificatePath(),
          "node." + node.getNodeId() + ".tlsCert", passed, failed);
      checkFile(node.getPrivilegeConfigPath(),
          "node." + node.getNodeId() + ".privilegeConfig", passed, failed);
    }
  }

  private void checkCatalog(List<String> passed, List<String> failed) {
    Path catalog = Paths.get(config.getCatalogPath()).toAbsolutePath();
    Path parent = catalog.getParent();
    record(parent == null || !Files.exists(parent) || Files.isDirectory(parent),
        "catalog.parent=" + parent, passed, failed);
    record(!config.getCatalogProperties().isEmpty(), "catalog.properties",
        passed, failed);
  }

  private void checkFile(String file, String name, List<String> passed,
      List<String> failed) {
    Path path = Paths.get(file);
    boolean nonEmpty = file != null && !file.trim().isEmpty();
    record(nonEmpty, name + ".configured=" + file, passed, failed);
    if (strictFiles) {
      record(nonEmpty && Files.isRegularFile(path), name + ".exists=" + path,
          passed, failed);
    }
  }

  private static boolean hasScript(Path binDir, String name) {
    return Files.isRegularFile(binDir.resolve(name))
        || Files.isRegularFile(binDir.resolve(name + ".bat"));
  }

  private static void record(boolean passedCheck, String name,
      List<String> passed, List<String> failed) {
    if (passedCheck) {
      passed.add(name);
    } else {
      failed.add(name);
    }
  }
}
