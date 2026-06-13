package net.xdob.vexra.adb.db;

import java.util.List;
import java.util.Locale;

/**
 * ADB SQL 分布式 scan 配置。
 *
 * <p>该配置由 h2db table engine `WITH` 参数解析而来，只控制当前表的 SQL 读路径。
 * 默认关闭，避免影响旧 `jdbc:adb:*` 单机行为。当前阶段仅用于测试级和显式 opt-in
 * 的 SQL 分布式 scan，不改变磁盘格式、JDBC URL 语义或 h2db parser。</p>
 */
public final class AdbSqlDistributedScanConfig {
  static final String ENABLED_PARAM = "adb.distributed.sql";
  static final String SPLIT_ROW_PARAM = "adb.distributed.split.row";
  static final String TIMEOUT_PARAM = "adb.distributed.scan.timeoutMillis";

  private final boolean enabled;
  private final Long splitRowId;
  private final long timeoutMillis;

  /**
   * 创建 SQL 分布式 scan 配置。
   *
   * @param enabled 是否启用 SQL 分布式 scan
   * @param splitRowId 可选测试 split rowId；null 表示单 region 全表范围
   * @param timeoutMillis 分布式 scan 超时时间，0 表示不限制
   */
  public AdbSqlDistributedScanConfig(boolean enabled, Long splitRowId,
      long timeoutMillis) {
    if (timeoutMillis < 0) {
      throw new IllegalArgumentException("timeoutMillis is negative: "
          + timeoutMillis);
    }
    this.enabled = enabled;
    this.splitRowId = splitRowId;
    this.timeoutMillis = timeoutMillis;
  }

  /**
   * 从 h2db table engine `WITH` 参数解析配置。
   *
   * @param params table engine 参数；可以为 null
   * @return SQL 分布式 scan 配置
   */
  public static AdbSqlDistributedScanConfig fromTableEngineParams(
      List<String> params) {
    boolean enabled = false;
    Long splitRowId = null;
    long timeoutMillis = 5000L;
    if (params != null) {
      for (String raw : params) {
        if (raw == null) {
          continue;
        }
        String param = raw.trim();
        int separator = param.indexOf('=');
        if (separator <= 0) {
          continue;
        }
        String key = param.substring(0, separator).trim()
            .toLowerCase(Locale.ROOT);
        String value = param.substring(separator + 1).trim();
        if (ENABLED_PARAM.equals(key)) {
          enabled = Boolean.parseBoolean(value);
        } else if (SPLIT_ROW_PARAM.equals(key)) {
          splitRowId = Long.valueOf(value);
        } else if (TIMEOUT_PARAM.equals(key)) {
          timeoutMillis = Long.parseLong(value);
        }
      }
    }
    return new AdbSqlDistributedScanConfig(enabled, splitRowId, timeoutMillis);
  }

  public boolean isEnabled() {
    return enabled;
  }

  public Long getSplitRowId() {
    return splitRowId;
  }

  public long getTimeoutMillis() {
    return timeoutMillis;
  }
}
