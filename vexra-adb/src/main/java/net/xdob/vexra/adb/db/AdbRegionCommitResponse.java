package net.xdob.vexra.adb.db;

import java.util.Objects;

/**
 * ADB region commit RPC 响应。
 *
 * <p>该对象是 transport 到 {@link AdbRpcRegionCommitClient} 的结果模型。真实
 * Raft/RPC 实现可以把状态机响应、leader 转发错误、epoch mismatch 或应用异常统一映射
 * 到该对象，再由 client 转换为事务提交路径可识别的异常。</p>
 */
public final class AdbRegionCommitResponse {
  private final AdbRegionCommitPhase phase;
  private final String regionId;
  private final boolean success;
  private final String message;
  private final Throwable cause;

  private AdbRegionCommitResponse(AdbRegionCommitPhase phase, String regionId,
      boolean success, String message, Throwable cause) {
    this.phase = Objects.requireNonNull(phase, "phase == null");
    this.regionId = normalize(regionId, "regionId");
    this.success = success;
    this.message = message == null ? "" : message.trim();
    this.cause = cause;
  }

  /**
   * 创建成功响应。
   *
   * @param phase commit 阶段
   * @param regionId region 标识
   * @return 成功响应
   */
  public static AdbRegionCommitResponse success(AdbRegionCommitPhase phase,
      String regionId) {
    return new AdbRegionCommitResponse(phase, regionId, true, "", null);
  }

  /**
   * 创建失败响应。
   *
   * @param phase commit 阶段
   * @param regionId region 标识
   * @param message 错误消息
   * @param cause 原始异常
   * @return 失败响应
   */
  public static AdbRegionCommitResponse failure(AdbRegionCommitPhase phase,
      String regionId, String message, Throwable cause) {
    return new AdbRegionCommitResponse(phase, regionId, false, message, cause);
  }

  public AdbRegionCommitPhase getPhase() {
    return phase;
  }

  public String getRegionId() {
    return regionId;
  }

  public boolean isSuccess() {
    return success;
  }

  public String getMessage() {
    return message;
  }

  public Throwable getCause() {
    return cause;
  }

  private static String normalize(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return value.trim();
  }
}
