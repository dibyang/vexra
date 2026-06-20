package net.xdob.vexra.adb.db;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ADB 后台 worker 诊断采集器。
 *
 * <p>采集器只读取 lock resolve 和 committed-version GC worker 已暴露的最近结果或
 * 最近失败，不会触发 resolve/GC，也不会修改 store。它面向 diagnostic bundle 的
 * operations/metrics 字段，帮助生产排查确认后台清理是否真的运行、最近是否失败。</p>
 */
public final class AdbBackgroundWorkerDiagnosticCollector {
  private final AdbLockResolveWorker lockResolveWorker;
  private final AdbCommittedVersionGcWorker gcWorker;

  /**
   * 创建后台 worker 诊断采集器。
   *
   * @param lockResolveWorker lock resolve worker；可为 null
   * @param gcWorker committed-version GC worker；可为 null
   */
  public AdbBackgroundWorkerDiagnosticCollector(
      AdbLockResolveWorker lockResolveWorker,
      AdbCommittedVersionGcWorker gcWorker) {
    this.lockResolveWorker = lockResolveWorker;
    this.gcWorker = gcWorker;
  }

  /**
   * 采集后台 worker 最近结果。
   *
   * @return 可并入诊断包的快照
   */
  public Snapshot collect() {
    Map<String, String> operations = new LinkedHashMap<>();
    Map<String, Number> metrics = new LinkedHashMap<>();
    collectLockResolve(operations, metrics);
    collectGc(operations, metrics);
    return new Snapshot(operations, metrics);
  }

  private void collectLockResolve(Map<String, String> operations,
      Map<String, Number> metrics) {
    if (lockResolveWorker == null) {
      operations.put("worker.lockResolve.present", "false");
      metrics.put("adb_worker_lock_resolve_present", 0);
      return;
    }
    operations.put("worker.lockResolve.present", "true");
    operations.put("worker.lockResolve.started",
        String.valueOf(lockResolveWorker.isStarted()));
    metrics.put("adb_worker_lock_resolve_present", 1);
    metrics.put("adb_worker_lock_resolve_started",
        lockResolveWorker.isStarted() ? 1 : 0);
    Optional<AdbLockResolveBatchResult> result =
        lockResolveWorker.getLastResult();
    operations.put("worker.lockResolve.lastResultPresent",
        String.valueOf(result.isPresent()));
    metrics.put("adb_worker_lock_resolve_last_result_present",
        result.isPresent() ? 1 : 0);
    if (result.isPresent()) {
      AdbLockResolveBatchResult value = result.get();
      operations.put("worker.lockResolve.scannedLocks",
          String.valueOf(value.getScannedLocks()));
      operations.put("worker.lockResolve.rolledBackLocks",
          String.valueOf(value.getRolledBackLocks()));
      operations.put("worker.lockResolve.rolledForwardLocks",
          String.valueOf(value.getRolledForwardLocks()));
      metrics.put("adb_worker_lock_resolve_scanned_locks",
          value.getScannedLocks());
      metrics.put("adb_worker_lock_resolve_rolled_back_locks",
          value.getRolledBackLocks());
      metrics.put("adb_worker_lock_resolve_rolled_forward_locks",
          value.getRolledForwardLocks());
    }
    putFailure("worker.lockResolve", "adb_worker_lock_resolve",
        lockResolveWorker.getLastFailure(), operations, metrics);
  }

  private void collectGc(Map<String, String> operations,
      Map<String, Number> metrics) {
    if (gcWorker == null) {
      operations.put("worker.gc.present", "false");
      metrics.put("adb_worker_gc_present", 0);
      return;
    }
    operations.put("worker.gc.present", "true");
    operations.put("worker.gc.started", String.valueOf(gcWorker.isStarted()));
    metrics.put("adb_worker_gc_present", 1);
    metrics.put("adb_worker_gc_started", gcWorker.isStarted() ? 1 : 0);
    Optional<AdbGcCleanResult> result = gcWorker.getLastResult();
    operations.put("worker.gc.lastResultPresent",
        String.valueOf(result.isPresent()));
    metrics.put("adb_worker_gc_last_result_present",
        result.isPresent() ? 1 : 0);
    if (result.isPresent()) {
      AdbGcCleanResult value = result.get();
      operations.put("worker.gc.scannedVersions",
          String.valueOf(value.getScannedVersions()));
      operations.put("worker.gc.deletedVersions",
          String.valueOf(value.getDeletedVersions()));
      metrics.put("adb_worker_gc_scanned_versions",
          value.getScannedVersions());
      metrics.put("adb_worker_gc_deleted_versions",
          value.getDeletedVersions());
    }
    putFailure("worker.gc", "adb_worker_gc", gcWorker.getLastFailure(),
        operations, metrics);
  }

  private static void putFailure(String operationsPrefix, String metricPrefix,
      Optional<SQLException> failure, Map<String, String> operations,
      Map<String, Number> metrics) {
    operations.put(operationsPrefix + ".lastFailurePresent",
        String.valueOf(failure.isPresent()));
    metrics.put(metricPrefix + "_last_failure_present",
        failure.isPresent() ? 1 : 0);
    if (failure.isPresent()) {
      SQLException error = failure.get();
      operations.put(operationsPrefix + ".lastFailureClass",
          error.getClass().getSimpleName());
      operations.put(operationsPrefix + ".lastFailureMessage",
          safe(error.getMessage()));
      operations.put(operationsPrefix + ".lastFailureSqlState",
          safe(error.getSQLState()));
    }
  }

  private static String safe(String value) {
    return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ');
  }

  /**
   * 后台 worker 诊断快照。
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
