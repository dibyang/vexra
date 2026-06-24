package net.xdob.vexra.adb.ldb;

import net.xdob.vexra.adb.db.VersionReadSession;
import net.xdob.vexra.ldb.ReadSession;
import net.xdob.vexra.ldb.SnapshotCursor;
import net.xdob.vexra.ldb.util.Slice;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LDB ReadSession 适配层测试。
 */
class LdbVersionReadSessionTest {

  /**
   * 验证闭区间物理计数会直接委托给 LDB ReadSession。
   */
  @Test
  void shouldDelegateCountClosed() {
    RecordingReadSession delegate = new RecordingReadSession();
    delegate.count = 7L;
    LdbVersionReadSession session = new LdbVersionReadSession(delegate);
    byte[] begin = new byte[]{1};
    byte[] end = new byte[]{2};

    assertEquals(7L, session.countClosed(begin, end));
    assertArrayEquals(begin, delegate.countBegin);
    assertArrayEquals(end, delegate.countEnd);
    assertEquals(0, delegate.cursorCalls);
  }

  /**
   * 验证闭区间扫描会直接委托给 LDB ReadSession，并保持 view 传递。
   */
  @Test
  void shouldDelegateScanClosed() {
    RecordingReadSession delegate = new RecordingReadSession();
    LdbVersionReadSession session = new LdbVersionReadSession(delegate);
    byte[] begin = new byte[]{3};
    byte[] end = new byte[]{4};
    final Slice[] seen = new Slice[2];

    session.scanClosed(begin, end, new VersionReadSession.EntryVisitor() {
      @Override
      public void visit(Slice keyView, Slice valueView) {
        seen[0] = keyView;
        seen[1] = valueView;
      }
    });

    assertArrayEquals(begin, delegate.scanBegin);
    assertArrayEquals(end, delegate.scanEnd);
    assertSame(delegate.keyView, seen[0]);
    assertSame(delegate.valueView, seen[1]);
    assertEquals(0, delegate.cursorCalls);
  }

  /**
   * 验证关闭会释放底层 ReadSession。
   */
  @Test
  void shouldCloseDelegate() {
    RecordingReadSession delegate = new RecordingReadSession();
    LdbVersionReadSession session = new LdbVersionReadSession(delegate);

    session.close();

    assertTrue(delegate.closed);
  }

  private static final class RecordingReadSession implements ReadSession {
    private final Slice keyView = new Slice(new byte[]{9});
    private final Slice valueView = new Slice(new byte[]{8});
    private long count;
    private byte[] countBegin;
    private byte[] countEnd;
    private byte[] scanBegin;
    private byte[] scanEnd;
    private boolean closed;
    private int cursorCalls;

    @Override
    public long countClosed(byte[] begin, byte[] inclusiveEnd) {
      this.countBegin = begin;
      this.countEnd = inclusiveEnd;
      return count;
    }

    @Override
    public void scanClosed(byte[] begin, byte[] inclusiveEnd,
        SnapshotCursor.SnapshotCursorVisitor visitor) {
      this.scanBegin = begin;
      this.scanEnd = inclusiveEnd;
      visitor.visit(keyView, valueView);
    }

    @Override
    public SnapshotCursor cursor() {
      cursorCalls++;
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
      closed = true;
    }
  }
}
