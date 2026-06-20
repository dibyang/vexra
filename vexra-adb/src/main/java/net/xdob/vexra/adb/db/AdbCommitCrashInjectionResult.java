package net.xdob.vexra.adb.db;

import java.util.Objects;

/**
 * ADB 单个 commit 崩溃注入点的恢复结果。
 *
 * <p>该对象只承载 release gate 证据，不直接执行故障注入。真实 JUnit、进程级
 * smoke 或长稳平台在完成 kill/reopen、重试和恢复校验后，把结果转换为本结构。</p>
 */
public final class AdbCommitCrashInjectionResult {
  private final AdbCommitCrashInjectionPoint injectionPoint;
  private final boolean recovered;
  private final AdbDurableCommitState finalState;
  private final String notes;

  /**
   * 创建 commit 崩溃注入结果。
   *
   * @param injectionPoint 崩溃注入点
   * @param recovered 是否恢复到期望语义
   * @param finalState 恢复后的 durable marker 终态；prewrite 前可为空
   * @param notes 诊断说明、日志路径或失败原因
   */
  public AdbCommitCrashInjectionResult(
      AdbCommitCrashInjectionPoint injectionPoint,
      boolean recovered, AdbDurableCommitState finalState, String notes) {
    this.injectionPoint = Objects.requireNonNull(injectionPoint,
        "injectionPoint == null");
    this.recovered = recovered;
    this.finalState = finalState;
    this.notes = notes == null ? "" : notes.trim();
  }

  public AdbCommitCrashInjectionPoint getInjectionPoint() {
    return injectionPoint;
  }

  public boolean isRecovered() {
    return recovered;
  }

  public AdbDurableCommitState getFinalState() {
    return finalState;
  }

  public String getNotes() {
    return notes;
  }
}
