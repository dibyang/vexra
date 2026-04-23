package net.xdob.vexra.adb.rocks;

import net.xdob.vexra.adb.db.ScanDirection;
import net.xdob.vexra.adb.db.VersionScanSource;
import org.rocksdb.RocksIterator;

public final class RocksVersionEntryCursor implements VersionScanSource {

  private final RocksIterator it;
  private final ScanDirection direction;

  public RocksVersionEntryCursor(RocksIterator it, ScanDirection direction) {
    this.it = it;
    this.direction = direction;
  }

  @Override
  public ScanDirection direction() {
    return direction;
  }

  @Override
  public void seekToRangeStart(byte[] lowerInclusive, byte[] upperExclusive) {
    if (direction == ScanDirection.FORWARD) {
      it.seek(lowerInclusive);
      return;
    }

    // REVERSE
    if (upperExclusive == null) {
      it.seekToLast();
    } else {
      it.seekForPrev(upperExclusive);
      if (!it.isValid()) {
        it.seekToLast();
      }
    }
  }

  @Override
  public boolean isValid() {
    return it.isValid();
  }

  @Override
  public byte[] key() {
    return it.key();
  }

  @Override
  public byte[] value() {
    return it.value();
  }

  @Override
  public void advance() {
    if (direction == ScanDirection.FORWARD) {
      it.next();
    } else {
      it.prev();
    }
  }

  @Override
  public void close() {
    it.close();
  }
}
