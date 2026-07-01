package net.xdob.vexra.adb.ha2;

import net.xdob.vexra.adb.db.AdbControlPlaneSnapshot;
import net.xdob.vexra.adb.db.AdbPrimaryLockStatus;
import net.xdob.vexra.adb.db.AdbTxnLock;
import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.cluster.region.KeyRange;
import net.xdob.vexra.cluster.region.RegionMetadata;
import net.xdob.vexra.ha.ReplicaRole;
import net.xdob.vexra.ha.VirtualNodeMetadata;
import net.xdob.vexra.ha.VirtualNodeReplica;
import net.xdob.vexra.proto.adb.PrimaryLockStatusResult;
import net.xdob.vexra.proto.adb.ReadRequest;
import net.xdob.vexra.proto.adb.ReadResponse;
import net.xdob.vexra.proto.adb.WriteRequest;
import net.xdob.vexra.proto.adb.WriteResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADB routed primary lock 状态读取器测试。
 *
 * <p>验证 reader 会按控制面 route snapshot 将 primary key 路由到 region leader，
 * 再通过 leader 对应的 RClient 查询 primary-status read path。</p>
 */
class AdbRoutedPrimaryLockStatusReaderTest {

  /**
   * 验证 primary key 命中第二个 region 时，只会访问该 region 的 leader client。
   */
  @Test
  void shouldRoutePrimaryStatusToLeaderClient() throws Exception {
    RecordingClient nodeA = new RecordingClient(unknownResponse());
    RecordingClient nodeB = new RecordingClient(committedResponse(70));
    AdbRClientRegistry registry = new AdbRClientRegistry();
    registry.register("node-a", nodeA);
    registry.register("node-b", nodeB);

    AdbPrimaryLockStatus status = new AdbRoutedPrimaryLockStatusReader("adb",
        () -> snapshotWithTwoRegions(), registry)
        .readPrimaryStatus(lock(10, rowKey(150)));

    assertTrue(status.isCommitted());
    assertEquals(70, status.getCommitTs());
    assertFalse(nodeA.called);
    assertTrue(nodeB.called);
    assertEquals(10, nodeB.lastReadRequest.getPrimaryLockStatus().getTxnId());
  }

  /**
   * 验证 region 缺少 leader 时失败，避免 resolver 误判为 unknown 后回滚。
   */
  @Test
  void shouldFailWhenPrimaryRegionHasNoLeader() {
    AdbRClientRegistry registry = new AdbRClientRegistry();

    SQLException error = assertThrows(SQLException.class,
        () -> new AdbRoutedPrimaryLockStatusReader("adb",
            () -> new AdbControlPlaneSnapshot(1, Collections.singletonList(
                region("r1", rowKey(1), rowKey(100), ""))), registry)
            .readPrimaryStatus(lock(11, rowKey(10))));

    assertTrue(error.getMessage().contains("no leader"));
  }

  /**
   * 验证未注册 leader client 时失败，提示部署层补齐 client 注册。
   */
  @Test
  void shouldFailWhenLeaderClientIsMissing() {
    SQLException error = assertThrows(SQLException.class,
        () -> new AdbRoutedPrimaryLockStatusReader("adb",
            () -> snapshotWithTwoRegions(), new AdbRClientRegistry())
            .readPrimaryStatus(lock(12, rowKey(10))));

    assertTrue(error.getMessage().contains("No RClient registered"));
  }

  /**
   * 验证 primary key 不在任何 region 范围时失败。
   */
  @Test
  void shouldFailWhenPrimaryKeyCannotBeRouted() {
    SQLException error = assertThrows(SQLException.class,
        () -> new AdbRoutedPrimaryLockStatusReader("adb",
            () -> new AdbControlPlaneSnapshot(1, Collections.singletonList(
                region("r1", rowKey(1), rowKey(100), "node-a"))),
            new AdbRClientRegistry()).readPrimaryStatus(lock(13,
            rowKey(200))));

    assertTrue(error.getMessage().contains("Failed to route primary lock"));
  }

  /**
   * 验证 registry 支持替换和移除 client。
   */
  @Test
  void shouldRegisterReplaceAndUnregisterClients() {
    AdbRClientRegistry registry = new AdbRClientRegistry();
    RecordingClient first = new RecordingClient(unknownResponse());
    RecordingClient second = new RecordingClient(unknownResponse());

    registry.register("node-a", first);
    registry.register("node-a", second);

    assertSame(second, registry.get("node-a").get());
    assertEquals(1, registry.snapshot().size());

    registry.unregister("node-a");
    assertFalse(registry.get("node-a").isPresent());
  }

  private static AdbControlPlaneSnapshot snapshotWithTwoRegions() {
    return new AdbControlPlaneSnapshot(1, Arrays.asList(
        region("r1", rowKey(1), rowKey(100), "node-a"),
        region("r2", rowKey(100), rowKey(200), "node-b")));
  }

  private static RegionMetadata region(String regionId, RowKey start,
      RowKey end, String leaderId) {
    return new RegionMetadata(regionId,
        new KeyRange(start.toBytes(), end.toBytes()), 1,
        new VirtualNodeMetadata("vn-" + regionId, 1, leaderId,
            Arrays.asList(
                new VirtualNodeReplica("node-a", ReplicaRole.DATA_VOTER),
                new VirtualNodeReplica("node-b", ReplicaRole.DATA_VOTER),
                new VirtualNodeReplica("witness-a",
                    ReplicaRole.WITNESS_VOTER)),
            0, 0, 0));
  }

  private static AdbTxnLock lock(long txnId, RowKey primaryKey) {
    return new AdbTxnLock(txnId, primaryKey.toBytes(), primaryKey.toBytes(),
        1, "r1", 1);
  }

  private static RowKey rowKey(long rowId) {
    return RowKey.of(TabId.of(1, 0L), rowId);
  }

  private static ReadResponse committedResponse(long commitTs) {
    return ReadResponse.newBuilder()
        .setSuccess(true)
        .setPrimaryLockStatusResult(PrimaryLockStatusResult.newBuilder()
            .setCommitted(true)
            .setCommitTs(commitTs))
        .build();
  }

  private static ReadResponse unknownResponse() {
    return ReadResponse.newBuilder()
        .setSuccess(true)
        .setPrimaryLockStatusResult(PrimaryLockStatusResult.newBuilder()
            .setCommitted(false))
        .build();
  }

  private static final class RecordingClient implements RClient {
    private final ReadResponse response;
    private boolean called;
    private ReadRequest lastReadRequest;

    private RecordingClient(ReadResponse response) {
      this.response = response;
    }

    @Override
    public ReadResponse sendReadRequest(ReadRequest request) {
      this.called = true;
      this.lastReadRequest = request;
      return response;
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
