package net.xdob.vexra.adb.ldb;

import net.xdob.vexra.adb.db.ScanDirection;
import net.xdob.vexra.adb.db.VersionScanSource;
import net.xdob.vexra.ldb.SnapshotCursor;
import net.xdob.vexra.ldb.util.Slice;

/**
 * LDB 快照游标到 ADB 版本扫描接口的适配器。
 *
 * <p>该类只负责把 ADB 的正向/反向扫描语义映射到 LDB {@link SnapshotCursor}，
 * 不持有额外事务状态；调用方负责关闭底层快照游标。</p>
 */
public final class LdbVersionEntryCursor implements VersionScanSource {
  private final SnapshotCursor it;
  private final ScanDirection direction;

  /**
   * 创建游标适配器。
   *
   * @param it LDB 快照游标
   * @param direction ADB 扫描方向
   */
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
        // LDB seek(begin, end) 的语义是 [begin, end)。
        it.seek(lowerInclusive, upperExclusive);
      }
      return;
    }

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
        // LDB seekClosed(begin, end) 的语义是 [begin, end]。
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
