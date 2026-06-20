package net.xdob.vexra.adb.db;

/**
 * ADB 控制面配置记录。
 *
 * <p>该记录对应 GA-03 规划中的 `adb_cp_config`，用于把生产模式、能力开关或后续
 * 控制面参数以版本化 key/value 形式暴露给 system table 和诊断流程。</p>
 */
public final class AdbControlPlaneConfigRecord {
  private final String configKey;
  private final String value;
  private final long version;
  private final long updatedAtMillis;

  /**
   * 创建控制面配置记录。
   *
   * @param configKey 配置键
   * @param value 配置值
   * @param version 配置版本
   * @param updatedAtMillis 更新时间戳
   */
  public AdbControlPlaneConfigRecord(String configKey, String value,
      long version, long updatedAtMillis) {
    this.configKey = normalize(configKey, "configKey");
    this.value = value == null ? "" : value;
    this.version = nonNegative(version, "version");
    this.updatedAtMillis = nonNegative(updatedAtMillis, "updatedAtMillis");
  }

  public String getConfigKey() {
    return configKey;
  }

  public String getValue() {
    return value;
  }

  public long getVersion() {
    return version;
  }

  public long getUpdatedAtMillis() {
    return updatedAtMillis;
  }

  private static String normalize(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return value.trim();
  }

  private static long nonNegative(long value, String fieldName) {
    if (value < 0) {
      throw new IllegalArgumentException(fieldName + " is negative: "
          + value);
    }
    return value;
  }
}
