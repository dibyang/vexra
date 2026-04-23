package net.xdob.vexra.ldb;

import java.io.File;
import java.io.IOException;


public interface DBFactory {
  LDB open(File path, Options options)
      throws IOException;

  void destroy(File path, Options options)
      throws IOException;

  void repair(File path, Options options)
      throws IOException;
}
