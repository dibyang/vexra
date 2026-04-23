package net.xdob.vexra.adb;

import net.xdob.vexra.adb.db.AdbWriteBatch;
import net.xdob.vexra.adb.db.Meta;
import net.xdob.vexra.adb.db.ScanDirection;
import net.xdob.vexra.adb.db.VersionScanSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * DbStore - 每个 database 一个实例
 * - table prefix
 * - write batch 事务
 * - snapshot / checkpoint
 */
public interface DbStore extends AutoCloseable {
  byte[] get(byte[] key) throws SQLException;

  void put(byte[] key, byte[] value) throws SQLException;

  long addLong(byte[] key, long operand) throws SQLException;

  Optional<Long> getLong(byte[] key) throws SQLException;

  void putLong(byte[] key, long value) throws SQLException;

  void delete(byte[] key) throws SQLException;

  void deleteRange(byte[] startKey, byte[] endKey) throws SQLException;

  byte[] get(byte cfId, byte[] key) throws SQLException;

  void put(byte cfId, byte[] key, byte[] value) throws SQLException;

  long addLong(byte cfId, byte[] key, long delta) throws SQLException;

  Optional<Long> getLong(byte cfId, byte[] key) throws SQLException;

  void putLong(byte cfId, byte[] key, long value) throws SQLException;

  void delete(byte cfId, byte[] key) throws SQLException;

  void deleteRange(byte cfId, byte[] startKey, byte[] endKey) throws SQLException;

  void checkpoint(String targetDir) throws IOException;

  void restore(String sourceDir) throws IOException;

  void writeBatch(WriteBatchConsumer consumer) throws SQLException;

  default CompletableFuture<Void> writeBatchAsync(WriteBatchConsumer consumer) throws SQLException{
    return CompletableFuture.runAsync(() -> {
      try {
        writeBatch(consumer);
      } catch (SQLException e) {
        throw new RuntimeException(e);
      }
    });
  }

  void rollback(long txnId) throws SQLException;

  default CompletableFuture<Void> rollbackAsync(long txnId) throws SQLException{
    return CompletableFuture.runAsync(() -> {
      try {
        rollback(txnId);
      } catch (SQLException e) {
        throw new RuntimeException(e);
      }
    });
  }


  CompletableFuture<Void> commitAsync(long txnId, long commitTs, List<Meta> metas) throws SQLException;

  VersionScanSource openVersionScanSource(ScanDirection direction);

  VersionScanSource openVersionScanSource(byte cfId, ScanDirection direction);

  default byte[] encodeLong(long v) {
    return ByteBuffer.allocate(8)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putLong(v)
        .array();
  }


  default Optional<Long> decodeLong(byte[] bytes) {
    if (bytes == null) {
      return Optional.empty();
    }
    if (bytes.length != 8) {
      throw new IllegalArgumentException("Invalid counter bytes, len=" + bytes.length);
    }
    return Optional.of(ByteBuffer.wrap(bytes)
        .order(ByteOrder.LITTLE_ENDIAN)
        .getLong());
  }

  void close() throws IOException;


  @FunctionalInterface
  public interface WriteBatchConsumer {
    void accept(AdbWriteBatch batch) throws SQLException;
  }
}
