package net.xdob.vexra.adb;

import java.util.Map;
import java.util.Properties;
import net.xdob.vexra.adb.db.AdbProductionGuard;

/**
 * ADB 命令行入口的生产范围参数解析工具。
 *
 * <p>该工具只识别 `adb.production.*`、`adb.install.topology` 和 `adb.security.*`
 * 参数，不消费业务命令自己的参数。调用方可以在显式生产参数存在时构造
 * {@link AdbProductionGuard}，未传生产参数时继续保持旧的本地兼容行为。</p>
 */
final class AdbProductionCommandOptions {
  private AdbProductionCommandOptions() {
  }

  /**
   * 判断命令行参数中是否存在生产范围配置。
   *
   * @param values 已解析的 `--key value` 参数
   * @return 存在任一生产范围参数时返回 true
   */
  static boolean hasProductionProperties(Map<String, String> values) {
    return !productionProperties(values).isEmpty();
  }

  /**
   * 从命令行参数中构造生产范围 guard。
   *
   * @param values 已解析的 `--key value` 参数
   * @return 生产范围 guard；未传生产参数时返回默认单机 guard
   */
  static AdbProductionGuard productionGuard(Map<String, String> values) {
    Properties properties = productionProperties(values);
    if (properties.isEmpty()) {
      return AdbProductionGuard.singleNodeDefault();
    }
    return AdbProductionGuard.fromProperties(properties);
  }

  private static Properties productionProperties(Map<String, String> values) {
    Properties properties = new Properties();
    copyIfPresent(values, properties, AdbProductionGuard.MODE_KEY);
    copyIfPresent(values, properties, AdbProductionGuard.TOPOLOGY_KEY);
    copyIfPresent(values, properties, AdbProductionGuard.INSTALL_TOPOLOGY_KEY);
    copyIfPresent(values, properties, AdbProductionGuard.ALLOW_EXPERIMENTAL_KEY);
    copyIfPresent(values, properties, AdbProductionGuard.TLS_KEY);
    copyIfPresent(values, properties, AdbProductionGuard.AUTH_KEY);
    copyIfPresent(values, properties, AdbProductionGuard.LEAST_PRIVILEGE_KEY);
    return properties;
  }

  private static void copyIfPresent(Map<String, String> source,
      Properties target, String key) {
    String value = source.get(key);
    if (value != null && !value.trim().isEmpty()) {
      target.setProperty(key, value.trim());
    }
  }
}
