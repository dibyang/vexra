package net.xdob.vexra.adb.ha2;

import net.xdob.vexra.adb.db.AdbControlPlaneSnapshot;
import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.cluster.region.KeyRange;
import net.xdob.vexra.cluster.region.RegionMetadata;
import net.xdob.vexra.ha.ReplicaRole;
import net.xdob.vexra.ha.VirtualNodeMetadata;
import net.xdob.vexra.ha.VirtualNodeReplica;
import net.xdob.vexra.proto.adb.ReadRequest;
import net.xdob.vexra.proto.adb.ReadResponse;
import net.xdob.vexra.proto.adb.WriteRequest;
import net.xdob.vexra.proto.adb.WriteResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADB RClient registry 刷新器测试。
 *
 * <p>验证刷新器会按控制面快照自动补齐 leader client 映射，同时只移除自己
 * 托管的旧 leader 映射，避免误关或误删部署层手工注册的连接。</p>
 */
class AdbRClientRegistryRefresherTest {

  /**
   * 验证首次刷新会为当前 region leader 创建并注册 RClient。
   */
  @Test
  void shouldRegisterCurrentRegionLeaders() throws Exception {
    AdbRClientRegistry registry = new AdbRClientRegistry();
    RecordingFactory factory = new RecordingFactory();
    AdbRClientRegistryRefresher refresher =
        new AdbRClientRegistryRefresher(registry, factory);

    AdbRClientRegistryRefreshResult result = refresher.refresh(snapshot(
        region("r1", 1, 100, "node-a"),
        region("r2", 100, 200, "node-b")));

    assertEquals(Arrays.asList("node-a", "node-b"), factory.createdIds);
    assertEquals(2, result.getRegisteredClients());
    assertEquals(0, result.getRetainedClients());
    assertEquals(0, result.getUnregisteredClients());
    assertEquals(0, result.getRegionsWithoutLeader());
    assertTrue(registry.get("node-a").isPresent());
    assertTrue(registry.get("node-b").isPresent());
    assertEquals(2, result.getActiveLeaderIds().size());
  }

  /**
   * 验证 leader 变化后会复用仍活跃的 client、注册新 leader 并移除旧托管映射。
   */
  @Test
  void shouldRetainAndUnregisterManagedLeaders() throws Exception {
    AdbRClientRegistry registry = new AdbRClientRegistry();
    RecordingFactory factory = new RecordingFactory();
    AdbRClientRegistryRefresher refresher =
        new AdbRClientRegistryRefresher(registry, factory);

    refresher.refresh(snapshot(
        region("r1", 1, 100, "node-a"),
        region("r2", 100, 200, "node-b")));

    AdbRClientRegistryRefreshResult result = refresher.refresh(snapshot(
        region("r1", 1, 100, "node-b"),
        region("r2", 100, 200, "node-c")));

    assertEquals(Arrays.asList("node-a", "node-b", "node-c"),
        factory.createdIds);
    assertEquals(1, result.getRegisteredClients());
    assertEquals(1, result.getRetainedClients());
    assertEquals(1, result.getUnregisteredClients());
    assertFalse(registry.get("node-a").isPresent());
    assertTrue(registry.get("node-b").isPresent());
    assertTrue(registry.get("node-c").isPresent());
  }

  /**
   * 验证刷新器不会移除部署层手工注册但不属于当前快照的 client。
   */
  @Test
  void shouldNotUnregisterUnmanagedClient() throws Exception {
    AdbRClientRegistry registry = new AdbRClientRegistry();
    RecordingClient manualClient = new RecordingClient();
    registry.register("manual", manualClient);
    RecordingFactory factory = new RecordingFactory();
    AdbRClientRegistryRefresher refresher =
        new AdbRClientRegistryRefresher(registry, factory);

    refresher.refresh(snapshot(region("r1", 1, 100, "node-a")));
    refresher.refresh(snapshot(region("r1", 1, 100, "node-b")));

    assertSame(manualClient, registry.get("manual").get());
    assertFalse(registry.get("node-a").isPresent());
    assertTrue(registry.get("node-b").isPresent());
  }

  /**
   * 验证缺少 leader 的 region 只会被计数，不会触发工厂创建连接。
   */
  @Test
  void shouldCountRegionsWithoutLeader() throws Exception {
    AdbRClientRegistry registry = new AdbRClientRegistry();
    RecordingFactory factory = new RecordingFactory();
    AdbRClientRegistryRefresher refresher =
        new AdbRClientRegistryRefresher(registry, factory);

    AdbRClientRegistryRefreshResult result = refresher.refresh(snapshot(
        region("r1", 1, 100, ""),
        region("r2", 100, 200, "node-b")));

    assertEquals(Collections.singletonList("node-b"), factory.createdIds);
    assertEquals(1, result.getRegisteredClients());
    assertEquals(1, result.getRegionsWithoutLeader());
    assertTrue(registry.get("node-b").isPresent());
  }

  private static AdbControlPlaneSnapshot snapshot(RegionMetadata... regions) {
    return new AdbControlPlaneSnapshot(1, Arrays.asList(regions));
  }

  private static RegionMetadata region(String regionId, long startRow,
      long endRow, String leaderId) {
    return new RegionMetadata(regionId,
        new KeyRange(rowKey(startRow).toBytes(), rowKey(endRow).toBytes()), 1,
        new VirtualNodeMetadata("vn-" + regionId, 1, leaderId,
            Arrays.asList(
                new VirtualNodeReplica("node-a", ReplicaRole.DATA_VOTER),
                new VirtualNodeReplica("node-b", ReplicaRole.DATA_VOTER),
                new VirtualNodeReplica("node-c", ReplicaRole.DATA_VOTER),
                new VirtualNodeReplica("manual", ReplicaRole.DATA_VOTER),
                new VirtualNodeReplica("witness-a",
                    ReplicaRole.WITNESS_VOTER)),
            0, 0, 0));
  }

  private static RowKey rowKey(long rowId) {
    return RowKey.of(TabId.of(1, 0L), rowId);
  }

  private static final class RecordingFactory implements AdbRClientFactory {
    private final List<String> createdIds = new ArrayList<>();

    @Override
    public RClient create(String replicaId) throws SQLException {
      createdIds.add(replicaId);
      return new RecordingClient();
    }
  }

  private static final class RecordingClient implements RClient {
    @Override
    public ReadResponse sendReadRequest(ReadRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override
    public WriteResponse sendWriteRequest(WriteRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<ReadResponse> sendReadRequestAsync(
        ReadRequest request) {
      CompletableFuture<ReadResponse> future = new CompletableFuture<>();
      future.completeExceptionally(new UnsupportedOperationException());
      return future;
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
