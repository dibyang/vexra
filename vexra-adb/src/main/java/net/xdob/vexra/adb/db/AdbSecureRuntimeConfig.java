package net.xdob.vexra.adb.db;

import java.util.Objects;
import java.util.Properties;

/**
 * ADB 安全运行默认配置。
 *
 * <p>该对象集中描述分布式部署的认证、TLS 和最小权限开关。它不签发证书，也不读取密钥
 * 内容，只负责在安装模板生成前把安全默认值校验到可审计边界。</p>
 */
public final class AdbSecureRuntimeConfig {
  private final boolean distributedEnabled;
  private final boolean tlsEnabled;
  private final boolean authEnabled;
  private final boolean leastPrivilegeEnabled;
  private final String tlsCaPath;
  private final String tlsCertDir;
  private final String authTokenFile;
  private final String privilegeConfigDir;
  private final String serviceUser;

  public AdbSecureRuntimeConfig(boolean distributedEnabled,
      boolean tlsEnabled, boolean authEnabled, boolean leastPrivilegeEnabled,
      String tlsCaPath, String tlsCertDir, String authTokenFile,
      String privilegeConfigDir, String serviceUser) {
    this.distributedEnabled = distributedEnabled;
    this.tlsEnabled = tlsEnabled;
    this.authEnabled = authEnabled;
    this.leastPrivilegeEnabled = leastPrivilegeEnabled;
    this.tlsCaPath = requireText(tlsCaPath, "tlsCaPath");
    this.tlsCertDir = requireText(tlsCertDir, "tlsCertDir");
    this.authTokenFile = requireText(authTokenFile, "authTokenFile");
    this.privilegeConfigDir = requireText(privilegeConfigDir,
        "privilegeConfigDir");
    this.serviceUser = requireText(serviceUser, "serviceUser");
    validate();
  }

  /**
   * 从 properties 创建安全运行配置。
   *
   * @param properties 安全配置项
   * @return 安全运行配置
   */
  public static AdbSecureRuntimeConfig fromProperties(Properties properties) {
    Objects.requireNonNull(properties, "properties == null");
    return new AdbSecureRuntimeConfig(
        bool(properties.getProperty("adb.security.distributed"), true),
        bool(properties.getProperty("adb.security.tls.enabled"), true),
        bool(properties.getProperty("adb.security.auth.enabled"), true),
        bool(properties.getProperty("adb.security.leastPrivilege.enabled"),
            true),
        require(properties, "adb.security.tls.ca"),
        require(properties, "adb.security.tls.certDir"),
        require(properties, "adb.security.auth.tokenFile"),
        require(properties, "adb.security.privilege.dir"),
        optional(properties, "adb.security.serviceUser", "vexra"));
  }

  /**
   * 转换为已有分布式运行时安全门禁对象。
   *
   * @return 分布式运行时选项
   */
  public AdbDistributedRuntimeOptions toRuntimeOptions() {
    return new AdbDistributedRuntimeOptions(distributedEnabled, tlsEnabled,
        leastPrivilegeEnabled);
  }

  /**
   * 生成指定服务实例的 JVM 安全参数。
   *
   * @param serviceId 服务实例 id
   * @return JVM 参数
   */
  public String jvmOptions(String serviceId) {
    String id = requireText(serviceId, "serviceId");
    return "-Dvexra.adb.security.distributed=" + distributedEnabled
        + " -Dvexra.adb.tls.enabled=" + tlsEnabled
        + " -Dvexra.adb.tls.ca=" + arg(tlsCaPath)
        + " -Dvexra.adb.tls.cert=" + arg(childPath(tlsCertDir, id
            + ".pem"))
        + " -Dvexra.adb.auth.enabled=" + authEnabled
        + " -Dvexra.adb.auth.tokenFile=" + arg(authTokenFile)
        + " -Dvexra.adb.leastPrivilege.enabled="
        + leastPrivilegeEnabled
        + " -Dvexra.adb.privilegeConfig=" + arg(childPath(
            privilegeConfigDir, id + "-privileges.json"));
  }

  public String getServiceUser() {
    return serviceUser;
  }

  private void validate() {
    if (distributedEnabled && (!tlsEnabled || !authEnabled
        || !leastPrivilegeEnabled)) {
      throw new IllegalArgumentException(
          "distributed secure defaults require TLS, auth and least privilege");
    }
  }

  private static String childPath(String parent, String child) {
    String normalized = parent.replace('\\', '/');
    return normalized.endsWith("/") ? normalized + child : normalized + "/"
        + child;
  }

  private static String arg(String value) {
    if (value.indexOf(' ') < 0 && value.indexOf('"') < 0) {
      return value;
    }
    return "\"" + value.replace("\"", "\\\"") + "\"";
  }

  private static String require(Properties properties, String key) {
    return requireText(properties.getProperty(key), key);
  }

  private static String optional(Properties properties, String key,
      String defaultValue) {
    String value = trimToNull(properties.getProperty(key));
    return value == null ? defaultValue : value;
  }

  private static boolean bool(String value, boolean defaultValue) {
    String trimmed = trimToNull(value);
    return trimmed == null ? defaultValue : Boolean.parseBoolean(trimmed);
  }

  private static String requireText(String value, String name) {
    String text = trimToNull(value);
    if (text == null) {
      throw new IllegalArgumentException(name + " is required");
    }
    return text;
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
