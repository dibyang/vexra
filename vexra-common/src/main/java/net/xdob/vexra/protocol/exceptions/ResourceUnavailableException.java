package net.xdob.vexra.protocol.exceptions;

/**
 * A particular resource is unavailable.
 */
public class ResourceUnavailableException extends RaftException {
  public ResourceUnavailableException(String message) {
    super(message);
  }
}
