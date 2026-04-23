package net.xdob.vexra.ldb.util;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

public class Utils {

  public static void deleteDir(File dir) {
    if (dir.isDirectory()) {
      for (File f : dir.listFiles()) {
        deleteDir(f);
      }
    }
    dir.delete();
  }
  public static byte[] encodeLong(long v) {
    return ByteBuffer.allocate(8)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putLong(v)
        .array();
  }

  public static Optional<Long> decodeLong(byte[] bytes) {
    if (bytes == null) {
      return Optional.empty();
    }
    if (bytes.length != 8) {
      throw new IllegalArgumentException("Invalid counter bytes, len=" + bytes.length);
    }
    return Optional.of(ByteBuffer.wrap(bytes)
        .order(ByteOrder.LITTLE_ENDIAN)
        .getLong());
  }
}
