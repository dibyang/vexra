package net.xdob.vexra.util;

import net.xdob.vexra.retry.MultipleLinearRandomRetry;
import net.xdob.vexra.retry.RetryPolicies;
import net.xdob.vexra.retry.RetryPolicy;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * common 模块基础值对象和重试策略回归测试。
 *
 * 这些类型被配置、客户端和服务端共同使用，测试重点是解析、边界和等价语义，
 * 不涉及网络、磁盘或 Raft。
 */
class CommonValueTypesTest {
  /**
   * 验证时间字符串解析支持空白、下划线和不同单位，并保持等价值相等。
   */
  @Test
  void shouldParseTimeDurationWithUnitsAndUnderscores() {
    TimeDuration duration = TimeDuration.valueOf("1_500 ms", TimeUnit.SECONDS);

    assertEquals(1500L, duration.toLong(TimeUnit.MILLISECONDS));
    assertEquals(TimeDuration.valueOf(1500, TimeUnit.MILLISECONDS),
        TimeDuration.valueOf(1500000, TimeUnit.MICROSECONDS));
    assertEquals("1.5s", TimeDuration.valueOf(1500, TimeUnit.MILLISECONDS)
        .toString(TimeUnit.SECONDS, 1));
  }

  /**
   * 验证时间计算在不同单位之间能正确取最小单位并向上取整。
   */
  @Test
  void shouldAddSubtractAndRoundTimeDuration() {
    TimeDuration total = TimeDuration.valueOf(1, TimeUnit.SECONDS)
        .add(TimeDuration.valueOf(500, TimeUnit.MILLISECONDS));
    TimeDuration remain = total.subtract(TimeDuration.valueOf(250, TimeUnit.MILLISECONDS));

    assertEquals(1500L, total.toLong(TimeUnit.MILLISECONDS));
    assertEquals(1250L, remain.toLong(TimeUnit.MILLISECONDS));
    assertEquals(2_000_000L, TimeDuration.valueOf(1, TimeUnit.MILLISECONDS)
        .roundUpNanos(1_000_001L));
    assertThrows(ArithmeticException.class,
        () -> TimeDuration.ZERO.roundUpNanos(1L));
  }

  /**
   * 验证大小字符串解析和 int 边界转换。
   */
  @Test
  void shouldParseSizeInBytesAndDetectIntOverflow() {
    assertEquals(1024L, SizeInBytes.valueOf("1kb").getSize());
    assertEquals(2L * 1024L * 1024L, SizeInBytes.valueOf("2m").getSize());
    assertEquals(10, SizeInBytes.valueOf("10b").getSizeInt());
    assertThrows(ArithmeticException.class,
        () -> SizeInBytes.valueOf(((long) Integer.MAX_VALUE) + 1L).getSizeInt());
  }

  /**
   * 验证固定次数重试策略在达到最大次数后停止，并保留固定 sleepTime。
   */
  @Test
  void shouldStopRetryAfterMaximumAttempts() {
    RetryPolicy policy = RetryPolicies.retryUpToMaximumCountWithFixedSleep(
        2, TimeDuration.valueOf(10, TimeUnit.MILLISECONDS));

    RetryPolicy.Action first = policy.handleAttemptFailure(event(1));
    RetryPolicy.Action second = policy.handleAttemptFailure(event(2));

    assertTrue(first.shouldRetry());
    assertEquals(10L, first.getSleepTime().toLong(TimeUnit.MILLISECONDS));
    assertFalse(second.shouldRetry());
  }

  /**
   * 验证线性随机重试解析的成功和失败路径，避免非法配置静默变成可重试。
   */
  @Test
  void shouldParseMultipleLinearRandomRetryConfig() {
    MultipleLinearRandomRetry retry = MultipleLinearRandomRetry.parseCommaSeparated("100ms, 2, 1s, 1");
    assertNotNull(retry);

    assertTrue(retry.handleAttemptFailure(event(1)).shouldRetry());
    assertTrue(retry.handleAttemptFailure(event(3)).shouldRetry());
    assertFalse(retry.handleAttemptFailure(event(4)).shouldRetry());
    assertNull(MultipleLinearRandomRetry.parseCommaSeparated("100ms, 2, broken"));
    assertNull(MultipleLinearRandomRetry.parseCommaSeparated("0ms, 2"));
  }

  /**
   * 构造指定 attemptCount 的重试事件。
   */
  private static RetryPolicy.Event event(int attemptCount) {
    return () -> attemptCount;
  }
}
