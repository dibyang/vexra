package net.xdob.vexra.adb.db;

/**
 * ADB 分布式运行时选项。
 *
 * <p>该对象是分布式模式启用前的最小安全门面。默认配置保持单机模式；显式启用
 * 分布式模式时必须同时启用 TLS 和最小权限标记，避免测试配置在生产环境中静默打开
 * 写入能力。</p>
 */
public final class AdbDistributedRuntimeOptions {
  private final boolean distributedEnabled;
  private final boolean tlsEnabled;
  private final boolean leastPrivilegeEnabled;

  /**
   * 创建 ADB 分布式运行时选项。
   *
   * @param distributedEnabled 是否启用分布式模式
   * @param tlsEnabled 是否启用 TLS
   * @param leastPrivilegeEnabled 是否启用最小权限
   */
  public AdbDistributedRuntimeOptions(boolean distributedEnabled,
      boolean tlsEnabled, boolean leastPrivilegeEnabled) {
    if (distributedEnabled && (!tlsEnabled || !leastPrivilegeEnabled)) {
      throw new IllegalArgumentException(
          "distributed mode requires TLS and least privilege");
    }
    this.distributedEnabled = distributedEnabled;
    this.tlsEnabled = tlsEnabled;
    this.leastPrivilegeEnabled = leastPrivilegeEnabled;
  }

  /**
   * 创建默认单机配置。
   *
   * @return 默认关闭分布式模式的配置
   */
  public static AdbDistributedRuntimeOptions singleNodeDefault() {
    return new AdbDistributedRuntimeOptions(false, false, false);
  }

  public boolean isDistributedEnabled() {
    return distributedEnabled;
  }

  public boolean isTlsEnabled() {
    return tlsEnabled;
  }

  public boolean isLeastPrivilegeEnabled() {
    return leastPrivilegeEnabled;
  }
}
