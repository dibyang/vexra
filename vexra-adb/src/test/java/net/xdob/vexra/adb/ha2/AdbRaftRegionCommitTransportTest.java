package net.xdob.vexra.adb.ha2;

import net.xdob.vexra.adb.db.AdbRegionCommitPhase;
import net.xdob.vexra.adb.db.AdbRegionCommitRequest;
import net.xdob.vexra.adb.db.AdbRegionCommitResponse;
import net.xdob.vexra.adb.db.Meta;
import net.xdob.vexra.adb.key.DataKey;
import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.proto.adb.ReadRequest;
import net.xdob.vexra.proto.adb.ReadResponse;
import net.xdob.vexra.proto.adb.WriteRequest;
import net.xdob.vexra.proto.adb.WriteResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADB Raft region commit transport 测试。
 *
 * <p>测试覆盖 ADB-Prod-01 中 region commit transport 到现有 ADB Raft 写请求的映射：
 * PREWRITE 走空 batch fencing，COMMIT 走 Commit proto，ROLLBACK 走 Rollback proto。</p>
 */
class AdbRaftRegionCommitTransportTest {
  /**
   * 验证三个 region commit 阶段会映射为对应的 WriteRequest。
   */
  @Test
  void shouldMapCommitPhasesToAdbWriteRequests() {
    RecordingRClient client = new RecordingRClient();
    AdbRaftRegionCommitTransport transport =
        new AdbRaftRegionCommitTransport("adb", client);
    AdbRegionCommitRequest request = request();

    assertTrue(transport.sendAsync(AdbRegionCommitPhase.PREWRITE, request)
        .join().isSuccess());
    assertTrue(transport.sendAsync(AdbRegionCommitPhase.COMMIT, request)
        .join().isSuccess());
    assertTrue(transport.sendAsync(AdbRegionCommitPhase.ROLLBACK, request)
        .join().isSuccess());

    assertEquals(3, client.requests.size());
    assertTrue(client.requests.get(0).hasBatch());
    assertEquals(0, client.requests.get(0).getBatch().getEntriesCount());
    assertTrue(client.requests.get(1).hasCommit());
    assertEquals(10, client.requests.get(1).getCommit().getTxnId());
    assertEquals(11, client.requests.get(1).getCommit().getCommitTs());
    assertEquals(1, client.requests.get(1).getCommit().getMetasCount());
    assertTrue(client.requests.get(2).hasRollback());
    assertEquals(10, client.requests.get(2).getRollback().getTxnId());
  }

  /**
   * 验证 Raft 写失败会转换为 region commit 失败响应。
   */
  @Test
  void shouldMapFailedWriteResponse() {
    RecordingRClient client = new RecordingRClient();
    client.success = false;
    AdbRaftRegionCommitTransport transport =
        new AdbRaftRegionCommitTransport("adb", client);

    AdbRegionCommitResponse response = transport
        .sendAsync(AdbRegionCommitPhase.COMMIT, request()).join();

    assertFalse(response.isSuccess());
    assertEquals("r1", response.getRegionId());
    assertTrue(response.getMessage().contains("raft write failed"));
  }

  /**
   * 验证 transport 会把 RClient 异常转换为失败响应，而不是吞掉异常。
   */
  @Test
  void shouldMapRClientExceptionToFailureResponse() {
    RecordingRClient client = new RecordingRClient();
    client.failure = new SQLException("leader unavailable");
    AdbRaftRegionCommitTransport transport =
        new AdbRaftRegionCommitTransport("adb", client);

    AdbRegionCommitResponse response = transport
        .sendAsync(AdbRegionCommitPhase.COMMIT, request()).join();

    assertFalse(response.isSuccess());
    assertTrue(response.getMessage().contains("leader unavailable"));
    assertTrue(response.getCause() instanceof SQLException);
  }

  private static AdbRegionCommitRequest request() {
    return new AdbRegionCommitRequest("r1", 1, "node-a", 10,
        10, 11, "r1", rowKey(1), 3000, true,
        Collections.singletonList((DataKey) rowKey(1)),
        Collections.singletonList(Meta.of(new byte[] {1}, new byte[] {2})));
  }

  private static RowKey rowKey(long rowId) {
    return RowKey.of(TabId.of(1, 0L), rowId);
  }

  private static final class RecordingRClient implements RClient {
    private final List<WriteRequest> requests = new ArrayList<>();
    private boolean success = true;
    private SQLException failure;

    @Override
    public ReadResponse sendReadRequest(ReadRequest request) {
      throw new UnsupportedOperationException("read is not used");
    }

    @Override
    public WriteResponse sendWriteRequest(WriteRequest request) {
      throw new UnsupportedOperationException("sync write is not used");
    }

    @Override
    public CompletableFuture<ReadResponse> sendReadRequestAsync(
        ReadRequest request) {
      throw new UnsupportedOperationException("read is not used");
    }

    @Override
    public CompletableFuture<WriteResponse> sendWriteRequestAsync(
        WriteRequest request) {
      requests.add(request);
      if (failure != null) {
        CompletableFuture<WriteResponse> future = new CompletableFuture<>();
        future.completeExceptionally(failure);
        return future;
      }
      return CompletableFuture.completedFuture(WriteResponse.newBuilder()
          .setSuccess(success)
          .build());
    }

    @Override
    public void close() throws IOException {
    }
  }
}
