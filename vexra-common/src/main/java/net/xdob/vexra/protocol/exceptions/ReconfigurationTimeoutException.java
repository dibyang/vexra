package net.xdob.vexra.protocol.exceptions;

public class ReconfigurationTimeoutException extends RaftException {
  public ReconfigurationTimeoutException(String message) {
    super(message);
  }
}
