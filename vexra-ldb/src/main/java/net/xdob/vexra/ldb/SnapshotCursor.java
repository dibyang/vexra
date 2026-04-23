package net.xdob.vexra.ldb;


public interface SnapshotCursor extends AutoCloseable {
  boolean isValid();

  void seekToFirst();

  void seekToLast();

  void seek(byte[] target);

  void seekForPrev(byte[] target);

  void next();

  void prev();

  byte[] key();

  byte[] value();

  @Override
  void close();
}
