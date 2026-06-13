package net.xdob.vexra.adb.db;

import java.util.Objects;

/**
 * ADB 单类故障注入结果。
 *
 * <p>结果记录某一类故障注入次数、恢复次数和最终判断。长稳报告通过这些结果证明
 * 网络、leader、磁盘、节点和 witness 故障都经过验收。</p>
 */
public final class AdbFaultInjectionResult {
  private final AdbFaultInjectionType type;
  private final int injectedCount;
  private final int recoveredCount;
  private final boolean passed;
  private final String notes;

  /**
   * 创建故障注入结果。
   *
   * @param type 故障类型
   * @param injectedCount 注入次数
   * @param recoveredCount 恢复次数
   * @param passed 该故障场景是否通过
   * @param notes 诊断说明
   */
  public AdbFaultInjectionResult(AdbFaultInjectionType type, int injectedCount,
      int recoveredCount, boolean passed, String notes) {
    this.type = Objects.requireNonNull(type, "type == null");
    this.injectedCount = nonNegative(injectedCount, "injectedCount");
    this.recoveredCount = nonNegative(recoveredCount, "recoveredCount");
    if (recoveredCount > injectedCount) {
      throw new IllegalArgumentException(
          "recoveredCount exceeds injectedCount");
    }
    this.passed = passed;
    this.notes = notes == null ? "" : notes.trim();
  }

  public AdbFaultInjectionType getType() {
    return type;
  }

  public int getInjectedCount() {
    return injectedCount;
  }

  public int getRecoveredCount() {
    return recoveredCount;
  }

  public boolean isPassed() {
    return passed;
  }

  public String getNotes() {
    return notes;
  }

  /**
   * 判断注入故障是否全部恢复并通过。
   *
   * @return 全部注入均恢复且 passed 为 true 时返回 true
   */
  public boolean isFullyRecovered() {
    return passed && injectedCount > 0 && recoveredCount == injectedCount;
  }

  private static int nonNegative(int value, String fieldName) {
    if (value < 0) {
      throw new IllegalArgumentException(fieldName + " is negative");
    }
    return value;
  }
}
