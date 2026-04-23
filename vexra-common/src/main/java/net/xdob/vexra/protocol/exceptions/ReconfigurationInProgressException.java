package net.xdob.vexra.protocol.exceptions;

public class ReconfigurationInProgressException extends RaftException {
  public ReconfigurationInProgressException(String message) {
    super(message);
  }
}
