package net.xdob.vexra.client.retry;

import net.xdob.vexra.proto.raft.RaftClientRequestProto;
import net.xdob.vexra.retry.RetryPolicies;
import net.xdob.vexra.retry.RetryPolicy;
import net.xdob.vexra.util.TimeDuration;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * client 模块请求类型重试策略回归测试。
 *
 * 测试只验证 builder 和非 ClientRetryEvent 的默认分支，不构造真实 RaftClientRequest，
 * 避免引入 RPC 或集群依赖。
 */
class RequestTypeDependentRetryPolicyTest {
  /**
   * 验证普通 RetryPolicy.Event 会走默认无限重试且不 sleep 的策略。
   */
  @Test
  void shouldUseDefaultRetryForNonClientEvent() {
    RequestTypeDependentRetryPolicy policy = RequestTypeDependentRetryPolicy.newBuilder().build();
    RetryPolicy.Action action = policy.handleAttemptFailure(() -> 100);

    assertTrue(action.shouldRetry());
    assertEquals(0L, action.getSleepTime().toLong(TimeUnit.MILLISECONDS));
  }

  /**
   * 验证 builder 不允许重复配置同一种请求类型的 retryPolicy 或 timeout。
   */
  @Test
  void shouldRejectDuplicateTypeConfiguration() {
    RequestTypeDependentRetryPolicy.Builder retryBuilder = RequestTypeDependentRetryPolicy.newBuilder()
        .setRetryPolicy(RaftClientRequestProto.TypeCase.WRITE,
            RetryPolicies.retryUpToMaximumCountWithFixedSleep(1, TimeDuration.ONE_MILLISECOND));

    assertThrows(IllegalStateException.class,
        () -> retryBuilder.setRetryPolicy(RaftClientRequestProto.TypeCase.WRITE,
            RetryPolicies.noRetry()));

    RequestTypeDependentRetryPolicy.Builder timeoutBuilder = RequestTypeDependentRetryPolicy.newBuilder()
        .setTimeout(RaftClientRequestProto.TypeCase.READ, TimeDuration.ONE_SECOND);

    assertThrows(IllegalStateException.class,
        () -> timeoutBuilder.setTimeout(RaftClientRequestProto.TypeCase.READ, TimeDuration.ONE_MINUTE));
  }

  /**
   * 验证 toString 不会在空配置时抛异常，便于日志路径安全输出。
   */
  @Test
  void shouldRenderEmptyPolicySafely() {
    String text = RequestTypeDependentRetryPolicy.newBuilder().build().toString();

    assertNotNull(text);
    assertFalse(text.isEmpty());
  }
}
