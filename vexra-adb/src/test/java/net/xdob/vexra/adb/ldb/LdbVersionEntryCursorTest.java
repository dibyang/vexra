package net.xdob.vexra.adb.ldb;

import net.xdob.vexra.adb.db.ScanDirection;
import net.xdob.vexra.ldb.SnapshotCursor;
import net.xdob.vexra.ldb.util.Slice;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * LDB 版本游标适配层测试。
 *
 * <p>这些用例锁定 ADB 对 vexra-ldb 低分配 cursor API 的接入方式，避免后续性能优化
 * 被退回到只调用复制型 key/value 或不传扫描上界的旧路径。</p>
 */
class LdbVersionEntryCursorTest {

  /**
   * 验证 forward 扫描会把独占上界传给 LDB bounded seek。
   */
  @Test
  void shouldPassUpperBoundToForwardSeek() {
    RecordingSnapshotCursor snapshotCursor = new RecordingSnapshotCursor();
    LdbVersionEntryCursor cursor = new LdbVersionEntryCursor(snapshotCursor,
        ScanDirection.FORWARD);
    byte[] lower = new byte[]{1, 2};
    byte[] upper = new byte[]{3, 4};

    cursor.seekToRangeStart(lower, upper);

    assertArrayEquals(lower, snapshotCursor.seekTarget);
    assertArrayEquals(upper, snapshotCursor.seekUpperBound);
  }

  /**
   * 验证 forward 闭区间扫描会转发给 LDB seekClosed。
   */
  @Test
  void shouldPassClosedUpperBoundToForwardSeekClosed() {
    RecordingSnapshotCursor snapshotCursor = new RecordingSnapshotCursor();
    LdbVersionEntryCursor cursor = new LdbVersionEntryCursor(snapshotCursor,
        ScanDirection.FORWARD);
    byte[] lower = new byte[]{1, 2};
    byte[] upper = new byte[]{3, 4};

    cursor.seekToRangeClosed(lower, upper);

    assertArrayEquals(lower, snapshotCursor.seekClosedTarget);
    assertArrayEquals(upper, snapshotCursor.seekClosedUpperBound);
  }

  /**
   * 验证 key/value view 会直接委托给 LDB SnapshotCursor。
   */
  @Test
  void shouldDelegateKeyAndValueViews() {
    RecordingSnapshotCursor snapshotCursor = new RecordingSnapshotCursor();
    LdbVersionEntryCursor cursor = new LdbVersionEntryCursor(snapshotCursor,
        ScanDirection.FORWARD);

    assertSame(snapshotCursor.keyView, cursor.keyView());
    assertSame(snapshotCursor.valueView, cursor.valueView());
  }

  /**
   * 验证物理记录计数会直接委托给 LDB SnapshotCursor。
   */
  @Test
  void shouldDelegateCountRemaining() {
    RecordingSnapshotCursor snapshotCursor = new RecordingSnapshotCursor();
    snapshotCursor.remainingCount = 3L;
    LdbVersionEntryCursor cursor = new LdbVersionEntryCursor(snapshotCursor,
        ScanDirection.FORWARD);

    assertEquals(3L, cursor.countRemaining());
    assertEquals(1, snapshotCursor.countRemainingCalls);
  }

  private static final class RecordingSnapshotCursor implements SnapshotCursor {
    private final Slice keyView = new Slice(new byte[]{1});
    private final Slice valueView = new Slice(new byte[]{2});
    private byte[] seekTarget;
    private byte[] seekUpperBound;
    private byte[] seekClosedTarget;
    private byte[] seekClosedUpperBound;
    private long remainingCount;
    private int countRemainingCalls;

    @Override
    public boolean isValid() {
      return true;
    }

    @Override
    public void seekToFirst() {
    }

    @Override
    public void seekToLast() {
    }

    @Override
    public void seek(byte[] target) {
      this.seekTarget = target;
    }

    @Override
    public void seek(byte[] target, byte[] exclusiveUpperBound) {
      this.seekTarget = target;
      this.seekUpperBound = exclusiveUpperBound;
    }

    @Override
    public void seekClosed(byte[] target, byte[] inclusiveUpperBound) {
      this.seekClosedTarget = target;
      this.seekClosedUpperBound = inclusiveUpperBound;
    }

    @Override
    public void seekForPrev(byte[] target) {
    }

    @Override
    public void next() {
    }

    @Override
    public void prev() {
    }

    @Override
    public byte[] key() {
      return keyView.copyBytes();
    }

    @Override
    public Slice keyView() {
      return keyView;
    }

    @Override
    public byte[] value() {
      return valueView.copyBytes();
    }

    @Override
    public Slice valueView() {
      return valueView;
    }

    @Override
    public long countRemaining() {
      countRemainingCalls++;
      return remainingCount;
    }

    @Override
    public void close() {
    }
  }
}
