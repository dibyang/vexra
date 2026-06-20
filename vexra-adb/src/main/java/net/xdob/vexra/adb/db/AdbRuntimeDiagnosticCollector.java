package net.xdob.vexra.adb.db;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ADB runtime 诊断采集器。
 *
 * <p>该采集器把 {@link AdbRuntimeOperationsBridge} 暴露的 system row 和 metrics
 * 转换为诊断包可以直接写入的结构化字段。它不创建 store 或控制面连接，调用方需要把
 * 已经处于当前进程内的 bridge 传入，因此适合嵌入式 runtime、测试和后续 live doctor
 * 入口复用。</p>
 */
public final class AdbRuntimeDiagnosticCollector {
  private final AdbRuntimeOperationsBridge operationsBridge;

  /**
   * 创建 runtime 诊断采集器。
   *
   * @param operationsBridge runtime 运维桥接器
   */
  public AdbRuntimeDiagnosticCollector(
      AdbRuntimeOperationsBridge operationsBridge) {
    this.operationsBridge = Objects.requireNonNull(operationsBridge,
        "operationsBridge == null");
  }

  /**
   * 采集 runtime system row 和 metrics。
   *
   * @param ddlRunning 当前是否存在运行中的 DDL
   * @return 可直接并入 {@link AdbDiagnosticBundle} 的 runtime 诊断快照
   */
  public Snapshot collect(boolean ddlRunning) {
    Map<String, String> operations = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry
        : operationsBridge.systemTableRow(ddlRunning).entrySet()) {
      operations.put("runtime." + entry.getKey(), entry.getValue());
    }
    return new Snapshot(operations, operationsBridge.metrics(ddlRunning));
  }

  /**
   * runtime 诊断快照。
   *
   * <p>operations 适合合并进诊断包 `[operations]` 段，metrics 适合合并进
   * `[metrics]` 段。两个 map 在构造后不可变，便于多消费者复用。</p>
   */
  public static final class Snapshot {
    private final Map<String, String> operations;
    private final Map<String, Number> metrics;

    private Snapshot(Map<String, String> operations,
        Map<String, Number> metrics) {
      this.operations = java.util.Collections.unmodifiableMap(
          new LinkedHashMap<>(Objects.requireNonNull(operations,
              "operations == null")));
      this.metrics = java.util.Collections.unmodifiableMap(
          new LinkedHashMap<>(Objects.requireNonNull(metrics,
              "metrics == null")));
    }

    public Map<String, String> getOperations() {
      return operations;
    }

    public Map<String, Number> getMetrics() {
      return metrics;
    }
  }
}
