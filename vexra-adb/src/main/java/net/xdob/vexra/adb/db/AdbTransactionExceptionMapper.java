package net.xdob.vexra.adb.db;

import java.sql.SQLException;
import java.util.Locale;
import java.util.concurrent.CompletionException;

/**
 * ADB 事务异常映射器。
 *
 * <p>该类只把明确的事务冲突消息映射为稳定 SQLState，路由、I/O、编码和未知
 * SQLException 会原样返回，避免生产故障被误分类为可重试冲突。</p>
 */
final class AdbTransactionExceptionMapper {
  private AdbTransactionExceptionMapper() {
  }

  /**
   * 将事务提交链路中的异常映射为调用方可识别的 SQLException。
   *
   * @param error 原始异常，允许是 CompletionException 包裹
   * @return 原始 SQLException 或带稳定 SQLState 的事务冲突异常
   */
  static Throwable map(Throwable error) {
    Throwable unwrapped = unwrap(error);
    if (unwrapped instanceof AdbTransactionConflictException) {
      return unwrapped;
    }
    if (unwrapped instanceof SQLException && isKnownConflict(unwrapped)) {
      String message = unwrapped.getMessage();
      return new AdbTransactionConflictException(
          "ADB transaction conflict: " + message, unwrapped);
    }
    return unwrapped;
  }

  private static Throwable unwrap(Throwable error) {
    Throwable current = error;
    while (current instanceof CompletionException && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private static boolean isKnownConflict(Throwable error) {
    String message = error.getMessage();
    if (message == null) {
      return false;
    }
    String normalized = message.toLowerCase(Locale.ROOT);
    return normalized.contains("adb prewrite lock conflict")
        || normalized.contains("adb prewrite conflict on intent key")
        || normalized.contains("adb prewrite write conflict")
        || normalized.contains("adb prewrite lock txn mismatch")
        || normalized.contains("duplicate commit idempotency key conflict")
        || normalized.contains("duplicate durable commit marker conflict");
  }
}
