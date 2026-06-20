package net.xdob.vexra.adb.db;

/**
 * ADB 生产拓扑类型。
 *
 * <p>该枚举只表达 ADB-GA-01 需要冻结的生产边界，不替代更底层的 Raft
 * replica role 或 HA 配置。生产集群推荐 `2data1witness`；纯 2 data 自动写入
 * 必须被拒绝。</p>
 */
public enum AdbProductionTopologyKind {
  /** 单机拓扑。 */
  SINGLE("single"),

  /** 两个数据节点加一个轻量 witness 的推荐生产拓扑。 */
  TWO_DATA_ONE_WITNESS("2data1witness"),

  /** 共享存储兼容拓扑，必须显式启用后才能作为过渡形态使用。 */
  SHARED_STORAGE("shared-storage"),

  /** 纯两个数据节点拓扑，禁止自动强一致写入。 */
  PURE_TWO_DATA("pure-2data");

  private final String configValue;

  AdbProductionTopologyKind(String configValue) {
    this.configValue = configValue;
  }

  public String getConfigValue() {
    return configValue;
  }

  /**
   * 从配置值解析拓扑类型。
   *
   * @param value 配置值
   * @return 拓扑类型
   */
  public static AdbProductionTopologyKind fromConfig(String value) {
    String normalized = normalize(value);
    if ("2-data-1-witness".equals(normalized)
        || "two-data-one-witness".equals(normalized)) {
      return TWO_DATA_ONE_WITNESS;
    }
    if ("pure-two-data".equals(normalized) || "2data".equals(normalized)
        || "two-data".equals(normalized)) {
      return PURE_TWO_DATA;
    }
    for (AdbProductionTopologyKind kind : values()) {
      if (kind.configValue.equals(normalized)
          || kind.name().equalsIgnoreCase(normalized.replace('-', '_'))) {
        return kind;
      }
    }
    throw new IllegalArgumentException("unsupported ADB production topology: "
        + value);
  }

  private static String normalize(String value) {
    if (value == null || value.trim().isEmpty()) {
      return SINGLE.configValue;
    }
    return value.trim().toLowerCase(java.util.Locale.ROOT);
  }
}
