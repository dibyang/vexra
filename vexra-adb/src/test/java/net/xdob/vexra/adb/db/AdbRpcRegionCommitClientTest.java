package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.DataKey;
import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.TabId;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADB region commit RPC client 测试。
 *
 * <p>测试覆盖 ADB-Prod-01 的首个生产化边界：2PC commit client 到可替换 transport
 * 的阶段映射、失败响应、transport 异常和 client 侧超时。</p>
 */
class AdbRpcRegionCommitClientTest {
  /**
   * 验证 prewrite/commit/rollback 会按阶段发送到 transport。
   */
  @Test
  void shouldSendAllCommitPhasesThroughTransport() {
    RecordingTransport transport = new RecordingTransport();
    try (AdbRpcRegionCommitClient client =
        new AdbRpcRegionCommitClient(transport, 1000)) {
      AdbRegionCommitRequest request = request("r1");

      client.prewriteAsync(request).join();
      client.commitAsync(request).join();
      client.rollbackAsync(request).join();

      assertEquals(3, transport.phases.size());
      assertEquals(AdbRegionCommitPhase.PREWRITE, transport.phases.get(0));
      assertEquals(AdbRegionCommitPhase.COMMIT, transport.phases.get(1));
      assertEquals(AdbRegionCommitPhase.ROLLBACK, transport.phases.get(2));
      assertSame(request, transport.requests.get(0));
    }
  }

  /**
   * 验证失败响应会转换成 SQLException，消息包含 region 和 leader。
   */
  @Test
  void shouldMapFailureResponseToSqlException() {
    RecordingTransport transport = new RecordingTransport();
    transport.failurePhase = AdbRegionCommitPhase.COMMIT;
    try (AdbRpcRegionCommitClient client =
        new AdbRpcRegionCommitClient(transport, 1000)) {
      CompletionException error = assertThrows(CompletionException.class,
          () -> client.commitAsync(request("r1")).join());

      assertTrue(error.getCause() instanceof SQLException);
      assertTrue(error.getCause().getMessage().contains("regionId=r1"));
      assertTrue(error.getCause().getMessage().contains("leaderId=node-a"));
      assertTrue(error.getCause().getMessage().contains("raft apply failed"));
    }
  }

  /**
   * 验证 transport 抛出的运行时异常会通过 future 暴露给上层。
   */
  @Test
  void shouldExposeTransportException() {
    RecordingTransport transport = new RecordingTransport();
    transport.throwOnSend = true;
    try (AdbRpcRegionCommitClient client =
        new AdbRpcRegionCommitClient(transport, 1000)) {
      CompletionException error = assertThrows(CompletionException.class,
          () -> client.prewriteAsync(request("r1")).join());

      assertTrue(error.getCause() instanceof IllegalStateException);
      assertEquals("transport unavailable", error.getCause().getMessage());
    }
  }

  /**
   * 验证未完成的 transport future 会触发 client 侧超时。
   */
  @Test
  void shouldTimeoutWhenTransportDoesNotComplete() {
    RecordingTransport transport = new RecordingTransport();
    transport.neverComplete = true;
    try (AdbRpcRegionCommitClient client =
        new AdbRpcRegionCommitClient(transport, 20)) {
      CompletionException error = assertThrows(CompletionException.class,
          () -> client.commitAsync(request("r1")).join());

      assertTrue(error.getCause() instanceof SQLException);
      assertTrue(error.getCause().getMessage().contains("Timed out"));
      assertTrue(error.getCause().getMessage().contains("timeoutMillis=20"));
    }
  }

  private static AdbRegionCommitRequest request(String regionId) {
    return new AdbRegionCommitRequest(regionId, 1, "node-a", 10,
        10, 11, "r1", rowKey(1), 3000, true,
        Collections.singletonList((DataKey) rowKey(1)),
        Collections.<Meta>emptyList());
  }

  private static RowKey rowKey(long rowId) {
    return RowKey.of(TabId.of(1, 0L), rowId);
  }

  private static final class RecordingTransport
      implements AdbRegionCommitTransport {
    private final List<AdbRegionCommitPhase> phases = new ArrayList<>();
    private final List<AdbRegionCommitRequest> requests = new ArrayList<>();
    private AdbRegionCommitPhase failurePhase;
    private boolean throwOnSend;
    private boolean neverComplete;

    @Override
    public CompletableFuture<AdbRegionCommitResponse> sendAsync(
        AdbRegionCommitPhase phase, AdbRegionCommitRequest request) {
      if (throwOnSend) {
        throw new IllegalStateException("transport unavailable");
      }
      phases.add(phase);
      requests.add(request);
      if (neverComplete) {
        return new CompletableFuture<>();
      }
      if (phase == failurePhase) {
        return CompletableFuture.completedFuture(
            AdbRegionCommitResponse.failure(phase, request.getRegionId(),
                "raft apply failed", new SQLException("apply failed")));
      }
      return CompletableFuture.completedFuture(
          AdbRegionCommitResponse.success(phase, request.getRegionId()));
    }
  }
}
