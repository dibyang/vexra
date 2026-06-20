package net.xdob.vexra.adb.db;

import java.sql.SQLException;

/**
 * ADB 事务冲突异常。
 *
 * <p>该异常用于把可重试的事务写冲突、锁冲突和提交幂等冲突暴露为稳定
 * SQLState，避免生产调用方只能依赖错误消息字符串判断是否重试。</p>
 */
public class AdbTransactionConflictException extends SQLException {
  public static final String SQL_STATE = "ADB02";
  public static final int ERROR_CODE = 7201;

  /**
   * 创建事务冲突异常。
   *
   * @param message 对调用方可见的冲突说明
   */
  public AdbTransactionConflictException(String message) {
    super(message, SQL_STATE, ERROR_CODE);
  }

  /**
   * 创建带原始原因的事务冲突异常。
   *
   * @param message 对调用方可见的冲突说明
   * @param cause 原始冲突原因
   */
  public AdbTransactionConflictException(String message, Throwable cause) {
    super(message, SQL_STATE, ERROR_CODE, cause);
  }
}
