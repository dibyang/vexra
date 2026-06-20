package net.xdob.vexra.adb.db;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * ADB 诊断包内存模型。
 *
 * <p>该模型只保存已经采集完成的诊断快照，不直接读取磁盘、网络或数据库。命令行、
 * system table 或运行时 bridge 可以按需把配置、预检、指标和备注写入同一个诊断包，
 * 再交给 {@link AdbDiagnosticBundleWriter} 生成可归档文本。</p>
 */
public final class AdbDiagnosticBundle {
  private final String bundleId;
  private final long generatedAtMillis;
  private final String productVersion;
  private final String h2dbVersion;
  private final String ldbVersion;
  private final Map<String, String> redactedConfig;
  private final Map<String, String> operations;
  private final Map<String, Number> metrics;
  private final List<String> preflightLines;
  private final List<String> notes;

  /**
   * 创建诊断包。
   *
   * @param bundleId 诊断包唯一标识
   * @param generatedAtMillis 生成时间戳
   * @param productVersion ADB 版本
   * @param h2dbVersion h2db 版本
   * @param ldbVersion ldb 版本
   * @param redactedConfig 已脱敏配置
   * @param operations 运行时操作快照
   * @param metrics 指标快照
   * @param preflightLines 预检文本行
   * @param notes 诊断备注
   */
  public AdbDiagnosticBundle(String bundleId, long generatedAtMillis,
      String productVersion, String h2dbVersion, String ldbVersion,
      Map<String, String> redactedConfig, Map<String, String> operations,
      Map<String, Number> metrics, List<String> preflightLines,
      List<String> notes) {
    this.bundleId = requireText(bundleId, "bundleId");
    this.generatedAtMillis = generatedAtMillis;
    this.productVersion = textOrUnknown(productVersion);
    this.h2dbVersion = textOrUnknown(h2dbVersion);
    this.ldbVersion = textOrUnknown(ldbVersion);
    this.redactedConfig = immutableSortedMap(redactedConfig, "redactedConfig");
    this.operations = immutableSortedMap(operations, "operations");
    this.metrics = immutableSortedNumberMap(metrics, "metrics");
    this.preflightLines = immutableList(preflightLines, "preflightLines");
    this.notes = immutableList(notes, "notes");
  }

  public String getBundleId() {
    return bundleId;
  }

  public long getGeneratedAtMillis() {
    return generatedAtMillis;
  }

  public String getProductVersion() {
    return productVersion;
  }

  public String getH2dbVersion() {
    return h2dbVersion;
  }

  public String getLdbVersion() {
    return ldbVersion;
  }

  public Map<String, String> getRedactedConfig() {
    return redactedConfig;
  }

  public Map<String, String> getOperations() {
    return operations;
  }

  public Map<String, Number> getMetrics() {
    return metrics;
  }

  public List<String> getPreflightLines() {
    return preflightLines;
  }

  public List<String> getNotes() {
    return notes;
  }

  private static Map<String, String> immutableSortedMap(
      Map<String, String> source, String fieldName) {
    Objects.requireNonNull(source, fieldName + " == null");
    return Collections.unmodifiableMap(new TreeMap<>(source));
  }

  private static Map<String, Number> immutableSortedNumberMap(
      Map<String, Number> source, String fieldName) {
    Objects.requireNonNull(source, fieldName + " == null");
    return Collections.unmodifiableMap(new TreeMap<>(source));
  }

  private static List<String> immutableList(List<String> source,
      String fieldName) {
    Objects.requireNonNull(source, fieldName + " == null");
    return Collections.unmodifiableList(new ArrayList<>(source));
  }

  private static String textOrUnknown(String value) {
    String text = trimToNull(value);
    return text == null ? "unknown" : text;
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
