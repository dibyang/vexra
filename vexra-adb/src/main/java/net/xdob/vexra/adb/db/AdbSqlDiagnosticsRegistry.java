package net.xdob.vexra.adb.db;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ADB SQL 诊断记录器注册表。
 *
 * <p>h2db table engine provider 会按数据库路径为每个 ADB 数据库注册一个轻量 recorder。
 * 该类只保留进程内诊断状态，不访问磁盘、不发起远程调用，供集成测试、后续 system table
 * 和 live doctor 入口读取最近 SQL 摘要。</p>
 */
public final class AdbSqlDiagnosticsRegistry {
  public static final long DEFAULT_SLOW_SQL_THRESHOLD_MILLIS =
      Long.getLong("vexra.adb.sql.diagnostic.slowMillis", 1_000L);
  public static final int DEFAULT_MAX_RECENT_EVENTS =
      Integer.getInteger("vexra.adb.sql.diagnostic.maxRecent", 32);

  private static final ConcurrentHashMap<String, AdbSqlDiagnosticRecorder>
      RECORDERS = new ConcurrentHashMap<>();

  private AdbSqlDiagnosticsRegistry() {
  }

  /**
   * 根据数据库路径生成稳定 scope。
   *
   * @param databasePath h2db database path
   * @return 进程内 SQL 诊断 scope
   */
  public static String scope(String databasePath) {
    String normalized = normalize(databasePath);
    return normalized.isEmpty() ? "database:mem" : "database:" + normalized;
  }

  /**
   * 获取或创建指定 scope 的 recorder。
   *
   * @param scope 诊断 scope
   * @return SQL 诊断 recorder
   */
  public static AdbSqlDiagnosticRecorder getOrCreate(String scope) {
    String key = normalizeScope(scope);
    return RECORDERS.computeIfAbsent(key, ignored ->
        new AdbSqlDiagnosticRecorder(DEFAULT_SLOW_SQL_THRESHOLD_MILLIS,
            DEFAULT_MAX_RECENT_EVENTS));
  }

  /**
   * 返回指定 scope 的 recorder。
   *
   * @param scope 诊断 scope
   * @return recorder；未创建时返回 null
   */
  public static AdbSqlDiagnosticRecorder get(String scope) {
    return RECORDERS.get(normalizeScope(scope));
  }

  /**
   * 返回所有 recorder 的当前快照。
   *
   * @return scope 到 SQL 诊断快照的不可变映射
   */
  public static Map<String, AdbSqlDiagnosticSnapshot> snapshotAll() {
    Map<String, AdbSqlDiagnosticSnapshot> snapshots = new LinkedHashMap<>();
    for (Map.Entry<String, AdbSqlDiagnosticRecorder> entry
        : RECORDERS.entrySet()) {
      snapshots.put(entry.getKey(), entry.getValue().snapshot());
    }
    return Collections.unmodifiableMap(snapshots);
  }

  /**
   * 重置所有已注册 recorder 的累计状态。
   *
   * <p>该方法保留 recorder 实例，适合 benchmark 在预热后清空统计窗口；已持有 recorder
   * 的 `TxnManager` 不需要重新绑定。</p>
   */
  public static void resetAll() {
    for (AdbSqlDiagnosticRecorder recorder : RECORDERS.values()) {
      recorder.clear();
    }
  }

  /**
   * 清空所有进程内 SQL 诊断状态。
   *
   * <p>该方法面向测试和嵌入式 runtime 重置，不会关闭数据库或修改持久化数据。</p>
   */
  public static void clear() {
    RECORDERS.clear();
  }

  private static String normalizeScope(String scope) {
    String normalized = normalize(scope);
    return normalized.isEmpty() ? "database:unknown" : normalized;
  }

  private static String normalize(String value) {
    if (value == null) {
      return "";
    }
    return value.trim().replace('\\', '/');
  }
}
