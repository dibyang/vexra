package net.xdob.vexra.adb.db;

import java.util.Locale;

/**
 * ADB live runtime 诊断连接配置。
 *
 * <p>该配置只描述 doctor 准备连接的 runtime 管理端点，不直接发起网络请求。
 * 当前 GA-06 阶段先冻结参数和校验规则，后续实现 HTTP/JMX/管理 RPC 时复用同一模型。</p>
 */
public final class AdbLiveRuntimeDiagnosticConfig {
  private final boolean enabled;
  private final String host;
  private final int port;
  private final int timeoutMillis;
  private final boolean tls;

  /**
   * 创建 live runtime 诊断配置。
   *
   * @param enabled 是否启用 live runtime 采集
   * @param host runtime 管理端点 host；启用时不能为空
   * @param port runtime 管理端点端口；启用时必须为 1..65535
   * @param timeoutMillis 连接/请求超时毫秒；启用时必须大于 0
   * @param tls 是否预期使用 TLS
   */
  public AdbLiveRuntimeDiagnosticConfig(boolean enabled, String host, int port,
      int timeoutMillis, boolean tls) {
    this.enabled = enabled;
    this.host = normalize(host);
    this.port = port;
    this.timeoutMillis = timeoutMillis;
    this.tls = tls;
    validate();
  }

  /**
   * 返回未启用 live runtime 采集的配置。
   *
   * @return disabled 配置
   */
  public static AdbLiveRuntimeDiagnosticConfig disabled() {
    return new AdbLiveRuntimeDiagnosticConfig(false, "", 0, 0, false);
  }

  public boolean isEnabled() {
    return enabled;
  }

  public String getHost() {
    return host;
  }

  public int getPort() {
    return port;
  }

  public int getTimeoutMillis() {
    return timeoutMillis;
  }

  public boolean isTls() {
    return tls;
  }

  /**
   * 返回 endpoint 摘要。
   *
   * @return host:port 或 disabled
   */
  public String endpoint() {
    return enabled ? host + ":" + port : "disabled";
  }

  private void validate() {
    if (!enabled) {
      return;
    }
    if (host.isEmpty()) {
      throw new IllegalArgumentException(
          "runtimeHost is required when liveRuntime is enabled");
    }
    if (port <= 0 || port > 65535) {
      throw new IllegalArgumentException("runtimePort is out of range: "
          + port);
    }
    if (timeoutMillis <= 0) {
      throw new IllegalArgumentException(
          "runtimeTimeoutMillis must be positive: " + timeoutMillis);
    }
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }
}
