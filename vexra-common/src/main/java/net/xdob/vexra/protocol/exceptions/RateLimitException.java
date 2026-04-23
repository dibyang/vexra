package net.xdob.vexra.protocol.exceptions;

/**
 * 超过限流异常
 */
public class RateLimitException extends RaftException {
	private final int permits;
  public RateLimitException(int permits) {
    this(permits, null);
	}

  public RateLimitException(int permits, Throwable t) {
    super("Rate limit exceeded, permits per second is " + permits, t);
		this.permits = permits;
	}

	public int getPermits() {
		return permits;
	}
}
