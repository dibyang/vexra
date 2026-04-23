package net.xdob.vexra.ldb;

import net.xdob.vexra.ldb.impl.InternalKey;
import net.xdob.vexra.ldb.util.Slice;

public interface RawCursor extends AutoCloseable {
  boolean isValid();

  void seekToFirst();
  void seekToLast();

  void seek(byte[] target);

  // 类 RocksDB：定位到 <= target 的最后一个
  void seekForPrev(byte[] target);
  void next();
  void prev();

  InternalKey key();
  Slice value();
  void close();
}
