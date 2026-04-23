package net.xdob.vexra.retry;

import net.xdob.vexra.util.JavaUtils;

/**
 * 不重试策略
 */
public final class NoRetry implements RetryPolicy {
  NoRetry() {
  }

  @Override
  public Action handleAttemptFailure(Event event) {
    return RetryPolicy.NO_RETRY_ACTION;
  }

  @Override
  public String toString() {
    return JavaUtils.getClassSimpleName(getClass());
  }
}
