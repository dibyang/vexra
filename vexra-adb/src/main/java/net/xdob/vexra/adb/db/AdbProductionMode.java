package net.xdob.vexra.adb.db;

/**
 * ADB 生产运行模式。
 *
 * <p>该枚举用于 ADB-GA-01 的生产范围冻结。不同模式决定哪些能力可以被
 * {@link AdbProductionGuard} 放行；未知或未显式开启的能力必须默认拒绝。</p>
 */
public enum AdbProductionMode {
  /** 单机兼容模式，保持旧 `jdbc:adb:*` 本地行为。 */
  SINGLE("single"),

  /** 第一版生产集群模式，只开放已验收的最小生产能力。 */
  MVP_CLUSTER("mvp-cluster"),

  /** 实验模式，只能在显式允许实验能力时启用高风险能力。 */
  EXPERIMENTAL("experimental");

  private final String configValue;

  AdbProductionMode(String configValue) {
    this.configValue = configValue;
  }

  public String getConfigValue() {
    return configValue;
  }

  /**
   * 从配置值解析生产运行模式。
   *
   * @param value 配置值，支持枚举名或短横线形式
   * @return 解析后的生产运行模式
   */
  public static AdbProductionMode fromConfig(String value) {
    String normalized = normalize(value);
    for (AdbProductionMode mode : values()) {
      if (mode.configValue.equals(normalized)
          || mode.name().equalsIgnoreCase(normalized.replace('-', '_'))) {
        return mode;
      }
    }
    throw new IllegalArgumentException("unsupported ADB production mode: "
        + value);
  }

  private static String normalize(String value) {
    if (value == null || value.trim().isEmpty()) {
      return SINGLE.configValue;
    }
    return value.trim().toLowerCase(java.util.Locale.ROOT);
  }
}
