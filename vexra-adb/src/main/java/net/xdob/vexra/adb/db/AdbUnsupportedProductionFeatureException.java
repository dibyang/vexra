package net.xdob.vexra.adb.db;

import java.sql.SQLException;

/**
 * ADB 生产 guard 拒绝未支持能力时使用的稳定异常。
 */
public class AdbUnsupportedProductionFeatureException extends SQLException {
  /** ADB 生产能力拒绝 SQLState。 */
  public static final String SQL_STATE = "ADB01";

  /** ADB 生产能力拒绝错误码。 */
  public static final int ERROR_CODE = 7101;

  /**
   * 创建生产能力拒绝异常。
   *
   * @param message 调用方可见错误消息
   */
  public AdbUnsupportedProductionFeatureException(String message) {
    super(message, SQL_STATE, ERROR_CODE);
  }

  /**
   * 创建带能力名称的生产能力拒绝异常。
   *
   * @param capability 被拒绝的能力
   * @param reason 拒绝原因
   */
  public AdbUnsupportedProductionFeatureException(
      AdbProductionCapability capability, String reason) {
    this("ADB production capability is not available: " + capability
        + ", reason=" + reason);
  }
}
