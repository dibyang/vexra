package net.xdob.vexra.adb.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * ADB 诊断 properties 采集器。
 *
 * <p>该采集器用于把 release evidence、backup/restore 报告或滚动升级报告等已经
 * 落盘的 properties 文件并入 doctor 诊断包。它只读取调用方显式传入的文件，不扫描
 * 目录，避免在故障现场意外收集过多或敏感文件。</p>
 */
public final class AdbDiagnosticPropertiesCollector {
  /**
   * 采集 properties 文件。
   *
   * @param paths properties 文件路径
   * @param prefix 写入 operations 区的 key 前缀
   * @return 可直接合并到诊断包 operations 区的字段
   * @throws IOException 文件存在但读取失败时抛出
   */
  public Map<String, String> collect(List<Path> paths, String prefix)
      throws IOException {
    Objects.requireNonNull(paths, "paths == null");
    String normalizedPrefix = requirePrefix(prefix);
    Map<String, String> values = new LinkedHashMap<>();
    for (int i = 0; i < paths.size(); i++) {
      collectOne(paths.get(i), normalizedPrefix + "." + i, values);
    }
    return values;
  }

  private static void collectOne(Path path, String prefix,
      Map<String, String> values) throws IOException {
    Objects.requireNonNull(path, "path == null");
    Path absolute = path.toAbsolutePath();
    values.put(prefix + ".path", absolute.toString());
    if (!Files.exists(path)) {
      values.put(prefix + ".status", "missing");
      return;
    }
    if (!Files.isRegularFile(path)) {
      values.put(prefix + ".status", "not-regular-file");
      return;
    }
    Properties properties = new Properties();
    try (InputStream input = Files.newInputStream(path)) {
      properties.load(input);
    }
    values.put(prefix + ".status", "loaded");
    for (String name : properties.stringPropertyNames()) {
      String key = prefix + "." + sanitize(name);
      String value = AdbDiagnosticBundleWriter.sensitive(name)
          ? AdbDiagnosticBundleWriter.REDACTED
          : properties.getProperty(name);
      values.put(key, value);
    }
  }

  private static String requirePrefix(String prefix) {
    if (prefix == null || prefix.trim().isEmpty()) {
      throw new IllegalArgumentException("prefix is required");
    }
    return sanitize(prefix.trim());
  }

  private static String sanitize(String value) {
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
          || (ch >= '0' && ch <= '9') || ch == '-' || ch == '_') {
        builder.append(ch);
      } else {
        builder.append('_');
      }
    }
    return builder.length() == 0 ? "value" : builder.toString();
  }
}
