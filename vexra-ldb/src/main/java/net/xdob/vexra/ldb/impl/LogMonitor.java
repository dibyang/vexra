package net.xdob.vexra.ldb.impl;

public interface LogMonitor {
  void corruption(long bytes, String reason);

  void corruption(long bytes, Throwable reason);
}
