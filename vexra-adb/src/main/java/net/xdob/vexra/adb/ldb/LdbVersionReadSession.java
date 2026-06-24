package net.xdob.vexra.adb.ldb;

import net.xdob.vexra.adb.db.VersionReadSession;
import net.xdob.vexra.ldb.ReadSession;
import net.xdob.vexra.ldb.SnapshotCursor;
import net.xdob.vexra.ldb.util.Slice;

/**
 * LDB ReadSession 到 ADB 版本读会话的适配器。
 *
 * <p>该类不提供线程安全保证，遵循 ldb ReadSession 的约束：推荐每个 benchmark worker
 * 或执行 worker 单独持有并关闭，避免跨线程共享同一个底层 cursor。</p>
 */
final class LdbVersionReadSession implements VersionReadSession {
  private final ReadSession session;

  LdbVersionReadSession(ReadSession session) {
    this.session = session;
  }

  @Override
  public long countClosed(byte[] beginInclusive, byte[] endInclusive) {
    return session.countClosed(beginInclusive, endInclusive);
  }

  @Override
  public void scanClosed(byte[] beginInclusive, byte[] endInclusive,
      final EntryVisitor visitor) {
    session.scanClosed(beginInclusive, endInclusive,
        new SnapshotCursor.SnapshotCursorVisitor() {
          @Override
          public void visit(Slice key, Slice value) {
            visitor.visit(key, value);
          }
        });
  }

  @Override
  public void close() {
    session.close();
  }
}
