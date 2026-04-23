package net.xdob.vexra.ldb.impl;

import static net.xdob.vexra.ldb.util.SizeOf.*;

public final class LogConstants {
  // todo find new home for these

  public static final int BLOCK_SIZE = 32768;

  // Header is checksum (4 bytes), type (1 byte), length (2 bytes).
  public static final int HEADER_SIZE = SIZE_OF_INT + SIZE_OF_BYTE + SIZE_OF_SHORT;

  private LogConstants() {
  }
}
