package net.xdob.vexra.adb.db;

import java.sql.SQLException;
import java.util.Collection;
import java.util.Objects;
import java.util.Properties;

/**
 * ADB 生产范围 guard。
 *
 * <p>该类实现 ADB-GA-01 的最小运行时边界：根据生产模式、拓扑、安全开关和
 * capability 判断请求是否可以进入生产路径。它不直接执行 SQL、事务或远程 RPC，
 * 只负责在不满足生产约束时尽早失败。</p>
 */
public final class AdbProductionGuard {
  public static final String MODE_KEY = "adb.production.mode";
  public static final String TOPOLOGY_KEY = "adb.production.topology";
  public static final String INSTALL_TOPOLOGY_KEY = "adb.install.topology";
  public static final String ALLOW_EXPERIMENTAL_KEY =
      "adb.production.allowExperimental";
  public static final String TLS_KEY = "adb.security.tls.enabled";
  public static final String AUTH_KEY = "adb.security.auth.enabled";
  public static final String LEAST_PRIVILEGE_KEY =
      "adb.security.leastPrivilege.enabled";

  private final AdbProductionMode mode;
  private final AdbProductionTopologyKind topologyKind;
  private final boolean tlsEnabled;
  private final boolean authEnabled;
  private final boolean leastPrivilegeEnabled;
  private final boolean allowExperimental;
  private final AdbProductionState state;
  private final String rejectionReason;

  /**
   * 创建生产范围 guard。
   *
   * @param mode 生产运行模式
   * @param topologyKind 生产拓扑
   * @param tlsEnabled 是否启用 TLS
   * @param authEnabled 是否启用认证
   * @param leastPrivilegeEnabled 是否启用最小权限
   * @param allowExperimental 是否允许实验能力
   */
  public AdbProductionGuard(AdbProductionMode mode,
      AdbProductionTopologyKind topologyKind, boolean tlsEnabled,
      boolean authEnabled, boolean leastPrivilegeEnabled,
      boolean allowExperimental) {
    this.mode = Objects.requireNonNull(mode, "mode == null");
    this.topologyKind = Objects.requireNonNull(topologyKind,
        "topologyKind == null");
    this.tlsEnabled = tlsEnabled;
    this.authEnabled = authEnabled;
    this.leastPrivilegeEnabled = leastPrivilegeEnabled;
    this.allowExperimental = allowExperimental;
    Validation validation = validateStartupState();
    this.state = validation.state;
    this.rejectionReason = validation.reason;
  }

  /**
   * 从 properties 解析生产范围 guard。
   *
   * @param properties 配置项
   * @return 生产范围 guard
   */
  public static AdbProductionGuard fromProperties(Properties properties) {
    Objects.requireNonNull(properties, "properties == null");
    String topology = value(properties, TOPOLOGY_KEY);
    if (topology == null) {
      topology = value(properties, INSTALL_TOPOLOGY_KEY);
    }
    return new AdbProductionGuard(
        AdbProductionMode.fromConfig(value(properties, MODE_KEY)),
        AdbProductionTopologyKind.fromConfig(topology),
        bool(properties, TLS_KEY, false),
        bool(properties, AUTH_KEY, false),
        bool(properties, LEAST_PRIVILEGE_KEY, false),
        bool(properties, ALLOW_EXPERIMENTAL_KEY, false));
  }

  /**
   * 创建默认单机 guard。
   *
   * @return 单机 guard
   */
  public static AdbProductionGuard singleNodeDefault() {
    return new AdbProductionGuard(AdbProductionMode.SINGLE,
        AdbProductionTopologyKind.SINGLE, false, false, false, false);
  }

  public AdbProductionMode getMode() {
    return mode;
  }

  public AdbProductionTopologyKind getTopologyKind() {
    return topologyKind;
  }

  public boolean isTlsEnabled() {
    return tlsEnabled;
  }

  public boolean isAuthEnabled() {
    return authEnabled;
  }

  public boolean isLeastPrivilegeEnabled() {
    return leastPrivilegeEnabled;
  }

  public boolean isAllowExperimental() {
    return allowExperimental;
  }

  public AdbProductionState getState() {
    return state;
  }

  public String getRejectionReason() {
    return rejectionReason;
  }

  /**
   * 要求 guard 已经进入可运行状态。
   *
   * @throws SQLException 当配置或拓扑不满足生产要求时抛出
   */
  public void requireReady() throws SQLException {
    if (state == AdbProductionState.REJECTED) {
      throw new AdbUnsupportedProductionFeatureException(
          "ADB production guard rejected startup: " + rejectionReason);
    }
  }

  /**
   * 校验指定生产能力是否可用。
   *
   * @param capability 目标能力
   * @param context 请求上下文
   * @throws SQLException 当能力不可用时抛出
   */
  public void requireCapability(AdbProductionCapability capability,
      AdbProductionRequestContext context) throws SQLException {
    Objects.requireNonNull(capability, "capability == null");
    Objects.requireNonNull(context, "context == null");
    requireReady();
    if (capability.isExperimentalOnly()) {
      if (mode == AdbProductionMode.EXPERIMENTAL && allowExperimental) {
        return;
      }
      throw unsupported(capability, "experimental capability is disabled");
    }
    switch (capability) {
      case LOCAL_SQL:
        return;
      case DISTRIBUTED_SQL:
      case SINGLE_REGION_TRANSACTION:
      case BASIC_ONLINE_DDL:
      case BACKUP_RESTORE:
      case ROLLING_UPGRADE:
        requireClusterCapability(capability);
        return;
      default:
        throw unsupported(capability, "capability is not part of production MVP");
    }
  }

  /**
   * 校验事务命中的 region 数量是否满足当前生产范围。
   *
   * @param regionIds 事务命中的 region id 集合
   * @param context 请求上下文
   * @throws SQLException 当跨 region 事务未被允许时抛出
   */
  public void validateTransactionRegions(Collection<String> regionIds,
      AdbProductionRequestContext context) throws SQLException {
    Objects.requireNonNull(context, "context == null");
    int count = distinctCount(regionIds);
    if (count <= 1) {
      requireCapability(AdbProductionCapability.SINGLE_REGION_TRANSACTION,
          context);
      return;
    }
    requireCapability(AdbProductionCapability.CROSS_REGION_TRANSACTION,
        context);
  }

  /**
   * 校验部署计划是否符合当前 guard 的生产拓扑约束。
   *
   * @param plan 部署计划
   * @throws SQLException 当部署计划与生产模式不匹配时抛出
   */
  public void validateClusterTopology(AdbDeploymentPlan plan)
      throws SQLException {
    Objects.requireNonNull(plan, "plan == null");
    if (mode == AdbProductionMode.SINGLE) {
      return;
    }
    int data = 0;
    int witness = 0;
    for (AdbDeploymentNodeSpec node : plan.getNodes()) {
      if (node.getRole() == AdbDeploymentNodeRole.DATA_NODE) {
        data++;
      } else if (node.getRole() == AdbDeploymentNodeRole.WITNESS_NODE) {
        witness++;
      }
    }
    if (data < 2 || witness < 1) {
      throw new AdbUnsupportedProductionFeatureException(
          "ADB MVP cluster requires at least 2 data nodes and 1 witness, data="
              + data + ", witness=" + witness);
    }
    if (!plan.getRuntimeOptions().isDistributedEnabled()) {
      throw new AdbUnsupportedProductionFeatureException(
          "ADB MVP cluster requires distributed runtime options");
    }
  }

  private void requireClusterCapability(AdbProductionCapability capability)
      throws SQLException {
    if (state != AdbProductionState.CLUSTER_READY) {
      throw unsupported(capability, "cluster mode is not ready, state="
          + state);
    }
  }

  private Validation validateStartupState() {
    if (mode == AdbProductionMode.SINGLE) {
      if (topologyKind == AdbProductionTopologyKind.PURE_TWO_DATA) {
        return rejected("pure 2-data topology is forbidden even in single mode");
      }
      return new Validation(AdbProductionState.SINGLE_READY, "");
    }
    if (!tlsEnabled || !authEnabled || !leastPrivilegeEnabled) {
      return rejected("mvp cluster requires TLS, auth and least privilege");
    }
    if (topologyKind == AdbProductionTopologyKind.PURE_TWO_DATA) {
      return rejected("pure 2-data automatic writes are forbidden");
    }
    if (topologyKind == AdbProductionTopologyKind.SINGLE) {
      return rejected("cluster mode requires 2 data nodes and 1 witness");
    }
    if (topologyKind == AdbProductionTopologyKind.SHARED_STORAGE
        && !(mode == AdbProductionMode.EXPERIMENTAL && allowExperimental)) {
      return rejected("shared-storage topology requires experimental opt-in");
    }
    return new Validation(AdbProductionState.CLUSTER_READY, "");
  }

  private AdbUnsupportedProductionFeatureException unsupported(
      AdbProductionCapability capability, String reason) {
    return new AdbUnsupportedProductionFeatureException(capability,
        reason + ", mode=" + mode + ", topology=" + topologyKind
            + ", request guard state=" + state);
  }

  private static Validation rejected(String reason) {
    return new Validation(AdbProductionState.REJECTED, reason);
  }

  private static String value(Properties properties, String key) {
    String value = properties.getProperty(key);
    if (value == null || value.trim().isEmpty()) {
      return null;
    }
    return value.trim();
  }

  private static boolean bool(Properties properties, String key,
      boolean defaultValue) {
    String value = value(properties, key);
    return value == null ? defaultValue : Boolean.parseBoolean(value);
  }

  private static int distinctCount(Collection<String> values) {
    if (values == null || values.isEmpty()) {
      return 0;
    }
    java.util.HashSet<String> distinct = new java.util.HashSet<>();
    for (String value : values) {
      if (value != null && !value.trim().isEmpty()) {
        distinct.add(value.trim());
      }
    }
    return distinct.size();
  }

  private static final class Validation {
    private final AdbProductionState state;
    private final String reason;

    private Validation(AdbProductionState state, String reason) {
      this.state = state;
      this.reason = reason;
    }
  }
}
