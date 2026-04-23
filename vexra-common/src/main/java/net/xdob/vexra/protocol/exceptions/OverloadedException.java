package net.xdob.vexra.protocol.exceptions;

/**
 * 超过负载异常
 */
public class OverloadedException extends RaftException {
	private final int limit;
  public OverloadedException(int limit) {
    this(limit, null);
	}

  public OverloadedException(int limit, Throwable t) {
    super("System overloaded limit is " + limit, t);
		this.limit = limit;
	}

	public int getLimit() {
		return limit;
	}
}
