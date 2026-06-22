package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.DbStore;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADB 写批次适配器测试。
 *
 * <p>覆盖本地 store 直写模式，避免 commit / bulk insert 热路径重新退回
 * WriteEn 中间对象收集模式。</p>
 */
class AdbWriteBatchTest {

  /**
   * 验证直写模式会直接调用底层 delegate，不创建 WriteEn 中间条目。
   */
  @Test
  void shouldWriteDirectlyToDelegateWithoutCollectingEntries() {
    RecordingDelegate delegate = new RecordingDelegate();
    AdbWriteBatch batch = AdbWriteBatch.direct(null, delegate);

    batch.put(bytes(1), bytes(2));
    batch.put(CF.META.getCfId(), bytes(3), bytes(4));
    batch.delete(bytes(5));
    batch.deleteRange(CF.TXN.getCfId(), bytes(6), bytes(7));

    assertTrue(batch.getEntries().isEmpty());
    assertEquals(4, batch.count());
    assertEquals(4, delegate.operations.size());
    assertEquals("put:DEFAULT:1:2", delegate.operations.get(0));
    assertEquals("put:META:3:4", delegate.operations.get(1));
    assertEquals("delete:DEFAULT:5", delegate.operations.get(2));
    assertEquals("deleteRange:TXN:6:7", delegate.operations.get(3));
  }

  /**
   * 验证直写模式会保留底层 SQLException，外层 store 可以把异常还原给调用方。
   */
  @Test
  void shouldWrapDelegateSqlExceptionInDirectMode() {
    RecordingDelegate delegate = new RecordingDelegate();
    SQLException failure = new SQLException("delegate failed");
    delegate.failure = failure;
    AdbWriteBatch batch = AdbWriteBatch.direct(null, delegate);

    AdbWriteBatch.DirectWriteBatchException error = assertThrows(
        AdbWriteBatch.DirectWriteBatchException.class,
        () -> batch.put(bytes(1), bytes(2)));

    assertSame(failure, error.getCause());
  }

  private static byte[] bytes(int value) {
    return new byte[]{(byte) value};
  }

  private static final class RecordingDelegate implements DelegateWriteBatch {
    private final List<String> operations = new ArrayList<>();
    private SQLException failure;

    @Override
    public DbStore getStore() {
      return null;
    }

    @Override
    public byte[] get(byte[] key) {
      return null;
    }

    @Override
    public void put(byte[] key, byte[] value) throws SQLException {
      failIfNeeded();
      operations.add("put:DEFAULT:" + key[0] + ":" + value[0]);
    }

    @Override
    public void put(CF cf, byte[] key, byte[] value) throws SQLException {
      failIfNeeded();
      operations.add("put:" + cf.name() + ":" + key[0] + ":" + value[0]);
    }

    @Override
    public void addLong(byte[] key, long delta) {
    }

    @Override
    public void addLong(CF cf, byte[] key, long delta) {
    }

    @Override
    public void delete(byte[] key) throws SQLException {
      failIfNeeded();
      operations.add("delete:DEFAULT:" + key[0]);
    }

    @Override
    public void delete(CF cf, byte[] key) throws SQLException {
      failIfNeeded();
      operations.add("delete:" + cf.name() + ":" + key[0]);
    }

    @Override
    public void deleteRange(byte[] beginKey, byte[] endKey)
        throws SQLException {
      failIfNeeded();
      operations.add("deleteRange:DEFAULT:" + beginKey[0] + ":" + endKey[0]);
    }

    @Override
    public void deleteRange(CF cf, byte[] beginKey, byte[] endKey)
        throws SQLException {
      failIfNeeded();
      operations.add("deleteRange:" + cf.name() + ":" + beginKey[0] + ":"
          + endKey[0]);
    }

    @Override
    public void rollbackToSavePoint() {
    }

    @Override
    public void popSavePoint() {
    }

    @Override
    public void setMaxBytes(long maxBytes) {
    }

    @Override
    public void close() {
    }

    private void failIfNeeded() throws SQLException {
      if (failure != null) {
        throw failure;
      }
    }
  }
}
