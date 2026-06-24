package net.xdob.vexra.adb.ldb;

import net.xdob.vexra.adb.db.ScanDirection;
import net.xdob.vexra.adb.db.VersionScanSource;
import net.xdob.vexra.ldb.SnapshotCursor;
import net.xdob.vexra.ldb.util.Slice;

public final class LdbVersionEntryCursor implements VersionScanSource {
  private final SnapshotCursor it;
  private final ScanDirection direction;

  public LdbVersionEntryCursor(SnapshotCursor it, ScanDirection direction) {
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
      if (lowerInclusive == null) {
        it.seekToFirst();
      } else {
        // ldb seek(begin, end) 语义为 [begin, end)，正好匹配 ADB
        // seekToRangeStart 的 upperExclusive 约束。
        it.seek(lowerInclusive, upperExclusive);
      }
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
  public void seekToRangeClosed(byte[] lowerInclusive, byte[] upperInclusive) {
    if (direction == ScanDirection.FORWARD) {
      if (lowerInclusive == null) {
        it.seekToFirst();
      } else {
        // ldb seekClosed(begin, end) 语义为 [begin, end]，用于 ADB
        // 逻辑闭区间 rowId 扫描映射出的完整物理 version-key 上界。
        it.seekClosed(lowerInclusive, upperInclusive);
      }
      return;
    }
    VersionScanSource.super.seekToRangeClosed(lowerInclusive, upperInclusive);
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
  public Slice keyView() {
    return it.keyView();
  }

  @Override
  public byte[] value() {
    return it.value();
  }

  @Override
  public Slice valueView() {
    return it.valueView();
  }

  @Override
  public boolean keyStartsWith(byte[] prefix) {
    return it.keyStartsWith(prefix);
  }

  @Override
  public boolean isKeyBefore(byte[] upperExclusive) {
    return it.isKeyBefore(upperExclusive);
  }

  @Override
  public long countRemaining() {
    return it.countRemaining();
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
