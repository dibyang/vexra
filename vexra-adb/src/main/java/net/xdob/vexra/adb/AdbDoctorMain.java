package net.xdob.vexra.adb;

import net.xdob.vexra.adb.db.AdbClusterOrchestrationConfig;
import net.xdob.vexra.adb.db.AdbClusterPreflightChecker;
import net.xdob.vexra.adb.db.AdbClusterPreflightReport;
import net.xdob.vexra.adb.db.AdbDiagnosticBundle;
import net.xdob.vexra.adb.db.AdbDiagnosticBundleWriter;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * ADB doctor 诊断命令行入口。
 *
 * <p>当前入口提供 GA-06 的第一层离线诊断包能力：读取集群 properties、执行同源
 * preflight、写出脱敏配置和版本信息。命令不启动节点、不连接业务库、不修改现有
 * 数据目录，因此可在故障现场安全重复执行。</p>
 */
public final class AdbDoctorMain {
  public static final String MAIN_CLASS = "net.xdob.vexra.adb.AdbDoctorMain";

  private AdbDoctorMain() {
  }

  /**
   * 生成 ADB 诊断包。
   *
   * @param args `--config path --output dir`，可选 `--bundleId id`、
   *             `--version version`、`--h2dbVersion version`、
   *             `--ldbVersion version`、`--strictFiles true|false` 和
   *             `--checkRuntimeScripts true|false`
   * @throws Exception 配置读取、预检或写入失败时抛出
   */
  public static void main(String[] args) throws Exception {
    Map<String, String> values = parseArgs(args);
    Path configPath = Paths.get(require(values, "config"));
    Path outputDir = Paths.get(require(values, "output"));
    Properties properties = loadProperties(configPath);
    AdbClusterPreflightReport preflight = new AdbClusterPreflightChecker(
        AdbClusterOrchestrationConfig.fromProperties(properties), properties,
        bool(values.get("strictFiles"), false),
        bool(values.get("checkRuntimeScripts"), true)).check();
    AdbDiagnosticBundle bundle = new AdbDiagnosticBundle(
        valueOrDefault(values.get("bundleId"),
            "adb-doctor-" + System.currentTimeMillis()),
        System.currentTimeMillis(),
        valueOrDefault(values.get("version"),
            AdbDoctorMain.class.getPackage().getImplementationVersion()),
        valueOrDefault(values.get("h2dbVersion"), "unknown"),
        valueOrDefault(values.get("ldbVersion"), "unknown"),
        AdbDiagnosticBundleWriter.redact(properties),
        operations(preflight, configPath),
        metrics(preflight),
        lines(preflight.render()),
        Collections.singletonList("offline diagnostic bundle"));
    Path file = new AdbDiagnosticBundleWriter().write(bundle, outputDir);
    System.out.println("BUNDLE " + file.toAbsolutePath());
  }

  private static Properties loadProperties(Path path) throws Exception {
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(path,
        StandardCharsets.UTF_8)) {
      properties.load(reader);
    }
    return properties;
  }

  private static Map<String, String> operations(
      AdbClusterPreflightReport preflight, Path configPath) {
    Map<String, String> operations = new LinkedHashMap<>();
    operations.put("configPath", configPath.toAbsolutePath().toString());
    operations.put("preflightPassed", String.valueOf(preflight.isPassed()));
    operations.put("preflightFailedChecks",
        String.valueOf(preflight.getFailedChecks().size()));
    operations.put("preflightPassedChecks",
        String.valueOf(preflight.getPassedChecks().size()));
    return operations;
  }

  private static Map<String, Number> metrics(
      AdbClusterPreflightReport preflight) {
    Map<String, Number> metrics = new LinkedHashMap<>();
    metrics.put("adb_doctor_preflight_passed",
        preflight.isPassed() ? 1 : 0);
    metrics.put("adb_doctor_preflight_failed_checks",
        preflight.getFailedChecks().size());
    metrics.put("adb_doctor_preflight_passed_checks",
        preflight.getPassedChecks().size());
    return metrics;
  }

  private static List<String> lines(String text) {
    return Arrays.asList(text.split("\\R"));
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

  private static String valueOrDefault(String value, String defaultValue) {
    if (value == null || value.trim().isEmpty()) {
      return defaultValue == null || defaultValue.trim().isEmpty()
          ? "unknown" : defaultValue;
    }
    return value.trim();
  }
}
