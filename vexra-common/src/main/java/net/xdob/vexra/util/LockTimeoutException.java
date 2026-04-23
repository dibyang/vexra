package net.xdob.vexra.util;

import java.util.concurrent.TimeoutException;

public class LockTimeoutException extends TimeoutException {
  public LockTimeoutException() {
    this("Lock timeout");
  }

  public LockTimeoutException(String message) {
    super(message);
  }
}
