package net.xdob.vexra.protocol.exceptions;

import net.xdob.vexra.protocol.RaftClientRequest;
import net.xdob.vexra.retry.RetryPolicy;

/**
 * Retry failure as per the {@link RetryPolicy} defined.
 */
public class RaftRetryFailureException extends RaftException {

  private final int attemptCount;

  public RaftRetryFailureException(
      RaftClientRequest request, int attemptCount, RetryPolicy retryPolicy, Throwable cause) {
    super("Failed " + request + " for " + attemptCount + " attempts with " + retryPolicy, cause);
    this.attemptCount = attemptCount;
  }

  public int getAttemptCount() {
    return attemptCount;
  }
}