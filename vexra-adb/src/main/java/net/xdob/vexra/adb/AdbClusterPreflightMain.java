package net.xdob.vexra.adb;

import net.xdob.vexra.adb.db.AdbClusterOrchestrationConfig;
import net.xdob.vexra.adb.db.AdbClusterPreflightChecker;
import net.xdob.vexra.adb.db.AdbClusterPreflightReport;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * ADB 集群预检命令行入口。
 *
 * <p>该命令读取 `adb-cluster-plan` 使用的同一份 properties 配置，执行生产部署前
 * 的安全、拓扑、runtime 脚本和目录预检。失败时以非 0 退出码结束，便于脚本或 CI
 * 阻断启动。</p>
 */
public final class AdbClusterPreflightMain {
  public static final String MAIN_CLASS =
      "net.xdob.vexra.adb.AdbClusterPreflightMain";

  private AdbClusterPreflightMain() {
  }

  /**
   * 执行集群预检。
   *
   * @param args `--config path`，可选 `--strictFiles true|false`
   *             和 `--checkRuntimeScripts true|false`
   * @throws Exception 配置读取失败时抛出
   */
  public static void main(String[] args) throws Exception {
    Map<String, String> values = parseArgs(args);
    Path configPath = Paths.get(require(values, "config"));
    Properties properties = loadProperties(configPath);
    AdbClusterPreflightReport report = new AdbClusterPreflightChecker(
        AdbClusterOrchestrationConfig.fromProperties(properties), properties,
        Boolean.parseBoolean(values.get("strictFiles")),
        bool(values.get("checkRuntimeScripts"), true)).check();
    System.out.print(report.render());
    if (!report.isPassed()) {
      System.exit(2);
    }
  }

  private static Properties loadProperties(Path path) throws Exception {
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(path,
        StandardCharsets.UTF_8)) {
      properties.load(reader);
    }
    return properties;
  }

  private static Map<String, String> parseArgs(String[] args) {
    Map<String, String> values = new HashMap<>();
    for (int i = 0; i < args.length; i += 2) {
      if (i + 1 >= args.length || !args[i].startsWith("--")) {
        throw new IllegalArgumentException("Illegal argument at index " + i);
      }
      values.put(args[i].substring(2), args[i + 1]);
    }
    return values;
  }

  private static String require(Map<String, String> values, String name) {
    String value = values.get(name);
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException("Missing argument: " + name);
    }
    return value.trim();
  }

  private static boolean bool(String value, boolean defaultValue) {
    return value == null ? defaultValue : Boolean.parseBoolean(value);
  }
}
