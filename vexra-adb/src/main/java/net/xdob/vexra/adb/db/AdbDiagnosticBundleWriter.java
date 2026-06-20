package net.xdob.vexra.adb.db;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeMap;

/**
 * ADB 诊断包写入器。
 *
 * <p>写入器负责把诊断快照渲染为稳定 UTF-8 文本，并在写配置前进行保守脱敏。它不
 * 启动集群、不连接业务数据库，也不清理日志目录，因此可以在故障现场重复执行。</p>
 */
public final class AdbDiagnosticBundleWriter {
  public static final String BUNDLE_FILE = "adb-diagnostic-bundle.txt";
  public static final String REDACTED = "<redacted>";

  /**
   * 写出诊断包。
   *
   * @param bundle 诊断包内容
   * @param outputDir 输出目录，不存在时创建
   * @return 写出的诊断包文件路径
   * @throws IOException 输出目录不可用或写入失败时抛出
   */
  public Path write(AdbDiagnosticBundle bundle, Path outputDir)
      throws IOException {
    Objects.requireNonNull(bundle, "bundle == null");
    Objects.requireNonNull(outputDir, "outputDir == null");
    if (Files.exists(outputDir) && !Files.isDirectory(outputDir)) {
      throw new IOException("diagnostic bundle output is not a directory: "
          + outputDir);
    }
    Files.createDirectories(outputDir);
    Path file = outputDir.resolve(BUNDLE_FILE);
    try (BufferedWriter writer = Files.newBufferedWriter(file,
        StandardCharsets.UTF_8)) {
      writeHeader(writer, bundle);
      writeSection(writer, "config", bundle.getRedactedConfig());
      writeSection(writer, "operations", bundle.getOperations());
      writeNumberSection(writer, "metrics", bundle.getMetrics());
      writeLogTails(writer, bundle.getLogTails());
      writeLines(writer, "preflight", bundle.getPreflightLines());
      writeLines(writer, "notes", bundle.getNotes());
    }
    return file;
  }

  /**
   * 对 properties 配置进行脱敏复制。
   *
   * @param properties 原始配置
   * @return key 排序后的脱敏配置
   */
  public static Map<String, String> redact(Properties properties) {
    Objects.requireNonNull(properties, "properties == null");
    Map<String, String> redacted = new TreeMap<>();
    for (String name : properties.stringPropertyNames()) {
      String value = properties.getProperty(name);
      redacted.put(name, sensitive(name) ? REDACTED : value);
    }
    return redacted;
  }

  /**
   * 判断配置 key 是否包含敏感语义。
   *
   * @param key 配置 key
   * @return 需要脱敏时返回 true
   */
  public static boolean sensitive(String key) {
    if (key == null) {
      return false;
    }
    String lower = key.toLowerCase(java.util.Locale.ROOT);
    return lower.contains("password")
        || lower.contains("token")
        || lower.contains("secret")
        || lower.contains("private")
        || lower.contains("credential")
        || lower.contains("tls")
        || lower.contains("cert")
        || lower.contains("privilege");
  }

  private static void writeHeader(BufferedWriter writer,
      AdbDiagnosticBundle bundle) throws IOException {
    writer.write("# ADB diagnostic bundle");
    writer.newLine();
    writer.write("bundleId=" + safe(bundle.getBundleId()));
    writer.newLine();
    writer.write("generatedAtMillis="
        + bundle.getGeneratedAtMillis());
    writer.newLine();
    writer.write("productVersion=" + safe(bundle.getProductVersion()));
    writer.newLine();
    writer.write("h2dbVersion=" + safe(bundle.getH2dbVersion()));
    writer.newLine();
    writer.write("ldbVersion=" + safe(bundle.getLdbVersion()));
    writer.newLine();
  }

  private static void writeSection(BufferedWriter writer, String name,
      Map<String, String> values) throws IOException {
    writer.write("[" + name + "]");
    writer.newLine();
    for (Map.Entry<String, String> entry : values.entrySet()) {
      writer.write(safe(entry.getKey()));
      writer.write('=');
      writer.write(safe(entry.getValue()));
      writer.newLine();
    }
  }

  private static void writeNumberSection(BufferedWriter writer, String name,
      Map<String, Number> values) throws IOException {
    writer.write("[" + name + "]");
    writer.newLine();
    for (Map.Entry<String, Number> entry : values.entrySet()) {
      writer.write(safe(entry.getKey()));
      writer.write('=');
      writer.write(String.valueOf(entry.getValue()));
      writer.newLine();
    }
  }

  private static void writeLines(BufferedWriter writer, String name,
      Iterable<String> lines) throws IOException {
    writer.write("[" + name + "]");
    writer.newLine();
    for (String line : lines) {
      writer.write(safe(line));
      writer.newLine();
    }
  }

  private static void writeLogTails(BufferedWriter writer,
      Map<String, java.util.List<String>> logTails) throws IOException {
    writer.write("[logTails]");
    writer.newLine();
    for (Map.Entry<String, java.util.List<String>> entry
        : logTails.entrySet()) {
      writer.write("--- " + safe(entry.getKey()));
      writer.newLine();
      for (String line : entry.getValue()) {
        writer.write(safe(line));
        writer.newLine();
      }
    }
  }

  private static String safe(String value) {
    if (value == null) {
      return "";
    }
    return value.replace('\r', ' ').replace('\n', ' ');
  }
}
