package net.xdob.vexra.protocol.exceptions;

/**
 * 处理超时异常
 */
public class RaftTimeoutException extends RaftException {

	public RaftTimeoutException(String message) {
		super(message);
	}

	public RaftTimeoutException(String message, Throwable cause) {
		super(message, cause);
	}
}
