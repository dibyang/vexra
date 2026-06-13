package net.xdob.vexra.adb.db;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * ADB 长稳压测验收标准。
 *
 * <p>标准由发布流程配置，评估器按它判断一份报告是否可以作为 release 证据。</p>
 */
public final class AdbLongRunAcceptanceCriteria {
  private final long minDurationMillis;
  private final long minOperations;
  private final double maxFailureRate;
  private final long maxP99LatencyMillis;
  private final int minCheckpointCycles;
  private final int minBackupRestoreCycles;
  private final int minGcCycles;
  private final Set<AdbFaultInjectionType> requiredFaultTypes;

  /**
   * 创建长稳验收标准。
   *
   * @param minDurationMillis 最小运行时长
   * @param minOperations 最小操作数
   * @param maxFailureRate 最大失败率
   * @param maxP99LatencyMillis 最大 P99 延迟
   * @param minCheckpointCycles 最小 checkpoint 循环次数
   * @param minBackupRestoreCycles 最小 backup/restore 循环次数
   * @param minGcCycles 最小 GC 循环次数
   * @param requiredFaultTypes 必须覆盖的故障类型
   */
  public AdbLongRunAcceptanceCriteria(long minDurationMillis,
      long minOperations, double maxFailureRate, long maxP99LatencyMillis,
      int minCheckpointCycles, int minBackupRestoreCycles, int minGcCycles,
      Set<AdbFaultInjectionType> requiredFaultTypes) {
    this.minDurationMillis = nonNegative(minDurationMillis,
        "minDurationMillis");
    this.minOperations = nonNegative(minOperations, "minOperations");
    if (maxFailureRate < 0 || maxFailureRate > 1) {
      throw new IllegalArgumentException("maxFailureRate out of range");
    }
    this.maxFailureRate = maxFailureRate;
    this.maxP99LatencyMillis = nonNegative(maxP99LatencyMillis,
        "maxP99LatencyMillis");
    this.minCheckpointCycles = nonNegative(minCheckpointCycles,
        "minCheckpointCycles");
    this.minBackupRestoreCycles = nonNegative(minBackupRestoreCycles,
        "minBackupRestoreCycles");
    this.minGcCycles = nonNegative(minGcCycles, "minGcCycles");
    Objects.requireNonNull(requiredFaultTypes, "requiredFaultTypes == null");
    if (requiredFaultTypes.isEmpty()) {
      throw new IllegalArgumentException("requiredFaultTypes is empty");
    }
    this.requiredFaultTypes = Collections.unmodifiableSet(
        EnumSet.copyOf(requiredFaultTypes));
  }

  public long getMinDurationMillis() {
    return minDurationMillis;
  }

  public long getMinOperations() {
    return minOperations;
  }

  public double getMaxFailureRate() {
    return maxFailureRate;
  }

  public long getMaxP99LatencyMillis() {
    return maxP99LatencyMillis;
  }

  public int getMinCheckpointCycles() {
    return minCheckpointCycles;
  }

  public int getMinBackupRestoreCycles() {
    return minBackupRestoreCycles;
  }

  public int getMinGcCycles() {
    return minGcCycles;
  }

  public Set<AdbFaultInjectionType> getRequiredFaultTypes() {
    return requiredFaultTypes;
  }

  private static long nonNegative(long value, String fieldName) {
    if (value < 0) {
      throw new IllegalArgumentException(fieldName + " is negative");
    }
    return value;
  }

  private static int nonNegative(int value, String fieldName) {
    if (value < 0) {
      throw new IllegalArgumentException(fieldName + " is negative");
    }
    return value;
  }
}
