package net.xdob.vexra.adb.ha2;

import net.xdob.vexra.adb.db.AdbPrimaryLockStatus;
import net.xdob.vexra.adb.db.AdbPrimaryLockStatusProto;
import net.xdob.vexra.adb.db.AdbTxnLock;
import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.proto.adb.PrimaryLockStatusResult;
import net.xdob.vexra.proto.adb.ReadRequest;
import net.xdob.vexra.proto.adb.ReadResponse;
import net.xdob.vexra.proto.adb.WriteRequest;
import net.xdob.vexra.proto.adb.WriteResponse;
import net.xdob.vexra.util.Proto2Util;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADB Raft primary lock 状态读取器测试。
 *
 * <p>验证跨 region primary 查询的 client 边界会发送专用
 * `ReadRequest.PrimaryLockStatus`，并把远端 committed/unknown/失败响应映射回
 * resolver 使用的 `AdbPrimaryLockStatus`。</p>
 */
class AdbRaftPrimaryLockStatusReaderTest {

  /**
   * 验证 committed 结果会保留远端返回的 commitTs。
   */
  @Test
  void shouldReadCommittedPrimaryStatus() throws Exception {
    RecordingClient client = new RecordingClient(ReadResponse.newBuilder()
        .setSuccess(true)
        .setPrimaryLockStatusResult(PrimaryLockStatusResult.newBuilder()
            .setCommitted(true)
            .setCommitTs(30))
        .build());

    AdbPrimaryLockStatus status = new AdbRaftPrimaryLockStatusReader("adb",
        client).readPrimaryStatus(lock(10, rowKey(1)));

    assertTrue(status.isCommitted());
    assertEquals(30, status.getCommitTs());
    assertTrue(client.lastReadRequest.hasPrimaryLockStatus());
    assertEquals(10, client.lastReadRequest.getPrimaryLockStatus().getTxnId());
    assertEquals(rowKey(1).toBytes().length,
        client.lastReadRequest.getPrimaryLockStatus().getPrimaryKey().size());
  }

  /**
   * 验证 unknown 结果会保持未提交状态。
   */
  @Test
  void shouldReadUnknownPrimaryStatus() throws Exception {
    RecordingClient client = new RecordingClient(ReadResponse.newBuilder()
        .setSuccess(true)
        .setPrimaryLockStatusResult(AdbPrimaryLockStatusProto.toProto(
            AdbPrimaryLockStatus.unknown()))
        .build());

    AdbPrimaryLockStatus status = new AdbRaftPrimaryLockStatusReader("adb",
        client).readPrimaryStatus(lock(11, rowKey(2)));

    assertFalse(status.isCommitted());
    assertEquals(0, status.getCommitTs());
  }

  /**
   * 验证远端失败响应会映射为 SQLException。
   */
  @Test
  void shouldMapFailedResponseToSQLException() {
    RecordingClient client = new RecordingClient(ReadResponse.newBuilder()
        .setSuccess(false)
        .setEx(Proto2Util.toThrowable2Proto(
            new SQLException("primary down")))
        .build());

    SQLException error = assertThrows(SQLException.class,
        () -> new AdbRaftPrimaryLockStatusReader("adb", client)
            .readPrimaryStatus(lock(12, rowKey(3))));

    assertTrue(error.getMessage().contains("primary down"));
  }

  /**
   * 验证成功响应缺少 primary-status result 时会失败，避免误判为 unknown。
   */
  @Test
  void shouldRejectMissingPrimaryStatusResult() {
    RecordingClient client = new RecordingClient(ReadResponse.newBuilder()
        .setSuccess(true)
        .build());

    SQLException error = assertThrows(SQLException.class,
        () -> new AdbRaftPrimaryLockStatusReader("adb", client)
            .readPrimaryStatus(lock(13, rowKey(4))));

    assertTrue(error.getMessage().contains("missing result"));
  }

  private static AdbTxnLock lock(long txnId, RowKey primaryKey) {
    return new AdbTxnLock(txnId, primaryKey.toBytes(), primaryKey.toBytes(),
        1, "r1", 1);
  }

  private static RowKey rowKey(long rowId) {
    return RowKey.of(TabId.of(1, 0L), rowId);
  }

  private static final class RecordingClient implements RClient {
    private final ReadResponse readResponse;
    private ReadRequest lastReadRequest;

    private RecordingClient(ReadResponse readResponse) {
      this.readResponse = readResponse;
    }

    @Override
    public ReadResponse sendReadRequest(ReadRequest request) {
      this.lastReadRequest = request;
      return readResponse;
    }

    @Override
    public WriteResponse sendWriteRequest(WriteRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<ReadResponse> sendReadRequestAsync(
        ReadRequest request) {
      return CompletableFuture.completedFuture(sendReadRequest(request));
    }

    @Override
    public CompletableFuture<WriteResponse> sendWriteRequestAsync(
        WriteRequest request) {
      CompletableFuture<WriteResponse> future = new CompletableFuture<>();
      future.completeExceptionally(new UnsupportedOperationException());
      return future;
    }

    @Override
    public void close() throws IOException {
    }
  }
}
