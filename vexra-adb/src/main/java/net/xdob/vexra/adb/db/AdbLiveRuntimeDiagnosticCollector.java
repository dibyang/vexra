package net.xdob.vexra.adb.db;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ADB live runtime 诊断采集器。
 *
 * <p>当前实现只把 doctor 的 live runtime 连接意图写入诊断包，不主动连接远程进程。
 * 这样可以先稳定 CLI 参数、输出字段和回滚语义，避免在 runtime 管理端点尚未定稿前让
 * doctor 对生产节点产生副作用。</p>
 */
public final class AdbLiveRuntimeDiagnosticCollector {
  public static final String STATUS_DISABLED = "disabled";
  public static final String STATUS_ENDPOINT_NOT_IMPLEMENTED =
      "endpoint_not_implemented";

  /**
   * 采集 live runtime 诊断摘要。
   *
   * @param config live runtime 诊断配置
   * @return 可合并进诊断包的快照
   */
  public Snapshot collect(AdbLiveRuntimeDiagnosticConfig config) {
    Objects.requireNonNull(config, "config == null");
    Map<String, String> operations = new LinkedHashMap<>();
    Map<String, Number> metrics = new LinkedHashMap<>();
    operations.put("liveRuntime.enabled", String.valueOf(config.isEnabled()));
    operations.put("liveRuntime.endpoint", config.endpoint());
    operations.put("liveRuntime.tls", String.valueOf(config.isTls()));
    operations.put("liveRuntime.timeoutMillis",
        String.valueOf(config.getTimeoutMillis()));
    metrics.put("adb_doctor_live_runtime_enabled",
        config.isEnabled() ? 1 : 0);
    metrics.put("adb_doctor_live_runtime_connected", 0);
    if (config.isEnabled()) {
      operations.put("liveRuntime.status", STATUS_ENDPOINT_NOT_IMPLEMENTED);
      return new Snapshot(operations, metrics,
          "live runtime diagnostic endpoint is not implemented yet");
    }
    operations.put("liveRuntime.status", STATUS_DISABLED);
    return new Snapshot(operations, metrics, "live runtime diagnostic disabled");
  }

  /**
   * live runtime 诊断快照。
   *
   * <p>operations 和 metrics 可直接合并进诊断包；note 用于说明本次 live 采集是否
   * 真的连接了 runtime，避免运维误判。</p>
   */
  public static final class Snapshot {
    private final Map<String, String> operations;
    private final Map<String, Number> metrics;
    private final String note;

    private Snapshot(Map<String, String> operations, Map<String, Number> metrics,
        String note) {
      this.operations = Collections.unmodifiableMap(new LinkedHashMap<>(
          Objects.requireNonNull(operations, "operations == null")));
      this.metrics = Collections.unmodifiableMap(new LinkedHashMap<>(
          Objects.requireNonNull(metrics, "metrics == null")));
      this.note = note == null ? "" : note.trim();
    }

    public Map<String, String> getOperations() {
      return operations;
    }

    public Map<String, Number> getMetrics() {
      return metrics;
    }

    public String getNote() {
      return note;
    }
  }
}
