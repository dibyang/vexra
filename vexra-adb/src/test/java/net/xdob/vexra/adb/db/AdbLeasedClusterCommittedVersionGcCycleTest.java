package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.adb.ldb.LdbStore;
import net.xdob.vexra.cluster.region.KeyRange;
import net.xdob.vexra.cluster.region.RegionMetadata;
import net.xdob.vexra.ha.ReplicaRole;
import net.xdob.vexra.ha.VirtualNodeMetadata;
import net.xdob.vexra.ha.VirtualNodeReplica;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADB 租约保护的集群 committed version GC cycle 测试。
 *
 * <p>测试覆盖 safe point lease、safe point 推进和 region GC 调度的组合语义，
 * 确保未持有租约的 worker 不派发 GC，持有租约的 worker 使用本轮持久化
 * safe point 做分片清理。</p>
 */
class AdbLeasedClusterCommittedVersionGcCycleTest {
  @TempDir
  private Path tempDir;

  /**
   * 验证未拿到 safe point lease 时不会派发任何 region GC 请求。
   */
  @Test
  void shouldSkipClusterGcWhenLeaseIsHeldByOtherOwner() throws Exception {
    try (LdbStore store = new LdbStore(
        tempDir.resolve("cycle-skip").toString())) {
      AdbSafePointLeaseStore leaseStore = new AdbSafePointLeaseStore(store);
      assertTrue(leaseStore.tryAcquire("owner-b", 100, 100).isPresent());
      RecordingGcClient client = new RecordingGcClient();
      AdbLeasedClusterCommittedVersionGcCycle cycle =
          newCycle(leaseStore, "owner-a", () -> 30,
              Collections::emptyList, () -> 120, client);

      AdbLeasedClusterCommittedVersionGcCycleResult result =
          cycle.runOnce(10, 1000);

      assertFalse(result.getSafePointResult().isLeaseAcquired());
      assertFalse(result.isGcDispatched());
      assertFalse(result.getGcResult().isPresent());
      assertTrue(client.requests.isEmpty());
    }
  }

  /**
   * 验证拿到 lease 后会推进 safe point，并用持久化 safe point 派发 region GC。
   */
  @Test
  void shouldAdvanceSafePointAndDispatchClusterGc() throws Exception {
    try (LdbStore store = new LdbStore(
        tempDir.resolve("cycle-dispatch").toString())) {
      AdbSafePointLeaseStore leaseStore = new AdbSafePointLeaseStore(store);
      RecordingGcClient client = new RecordingGcClient();
      client.complete("r1", new AdbGcCleanResult(4, 2));
      AdbLeasedClusterCommittedVersionGcCycle cycle =
          newCycle(leaseStore, "owner-a", () -> 30,
              Collections::emptyList, () -> 100, client);

      AdbLeasedClusterCommittedVersionGcCycleResult result =
          cycle.runOnce(7, 1000);

      assertTrue(result.getSafePointResult().isLeaseAcquired());
      assertTrue(result.isGcDispatched());
      assertEquals(30, result.getSafePointResult()
          .getLeaseRecord().getSafePoint());
      assertEquals(30, client.requests.get(0).getSafePoint());
      assertEquals(7, client.requests.get(0).getLimit());
      assertEquals(1, result.getGcResult().get().getCompletedRegions());
      assertEquals(2, result.getGcResult().get().getDeletedVersions());
    }
  }

  /**
   * 验证 safe point 被长事务阻塞时，cycle 仍使用当前持久化 safe point 保守调度。
   */
  @Test
  void shouldDispatchWithCurrentSafePointWhenAdvancementIsBlocked()
      throws Exception {
    try (LdbStore store = new LdbStore(
        tempDir.resolve("cycle-blocked").toString())) {
      AdbSafePointLeaseStore leaseStore = new AdbSafePointLeaseStore(store);
      assertTrue(leaseStore.tryAcquire("owner-a", 100, 20).isPresent());
      leaseStore.advanceSafePoint("owner-a", 10, 110);
      RecordingGcClient client = new RecordingGcClient();
      client.complete("r1", new AdbGcCleanResult(3, 1));
      AdbLeasedClusterCommittedVersionGcCycle cycle =
          newCycle(leaseStore, "owner-a", () -> 50,
              () -> Collections.singletonList(20L), () -> 115, client);

      AdbLeasedClusterCommittedVersionGcCycleResult result =
          cycle.runOnce(5, 1000);

      assertTrue(result.getSafePointResult().isLeaseAcquired());
      assertTrue(result.getSafePointResult().getAdvanceResult().get()
          .isBlockedByActiveTransaction());
      assertEquals(10, result.getSafePointResult()
          .getLeaseRecord().getSafePoint());
      assertEquals(10, client.requests.get(0).getSafePoint());
      assertEquals(1, result.getGcResult().get().getDeletedVersions());
    }
  }

  private static AdbLeasedClusterCommittedVersionGcCycle newCycle(
      AdbSafePointLeaseStore leaseStore, String ownerId,
      java.util.function.LongSupplier candidateSafePointSupplier,
      java.util.function.Supplier<java.util.Collection<Long>>
          activeStartTsSupplier,
      java.util.function.LongSupplier nowMillisSupplier,
      RecordingGcClient client) {
    AdbLeasedGlobalSafePointAdvancer advancer =
        new AdbLeasedGlobalSafePointAdvancer(leaseStore,
            new AdbGlobalSafePointAdvancer(new AdbGcSafePointManager(10),
                candidateSafePointSupplier, activeStartTsSupplier),
            ownerId, nowMillisSupplier, 50);
    AdbClusterCommittedVersionGcScheduler scheduler =
        new AdbClusterCommittedVersionGcScheduler(() -> snapshot(),
            () -> 0, client);
    return new AdbLeasedClusterCommittedVersionGcCycle(advancer,
        scheduler);
  }

  private static AdbControlPlaneSnapshot snapshot() {
    return new AdbControlPlaneSnapshot(7,
        Collections.singletonList(region("r1", 1, 100, "node-a")));
  }

  private static RegionMetadata region(String regionId, long startRow,
      long endRow, String leaderId) {
    return new RegionMetadata(regionId,
        new KeyRange(rowKey(startRow).toBytes(), rowKey(endRow).toBytes()),
        1, new VirtualNodeMetadata("vn-" + regionId, 1, leaderId,
            Arrays.asList(
                new VirtualNodeReplica("node-a", ReplicaRole.DATA_VOTER),
                new VirtualNodeReplica("node-b", ReplicaRole.DATA_VOTER),
                new VirtualNodeReplica("witness-a",
                    ReplicaRole.WITNESS_VOTER)),
            0, 0, 0));
  }

  private static RowKey rowKey(long rowId) {
    return RowKey.of(TabId.of(1, 0L), rowId);
  }

  private static final class RecordingGcClient
      implements AdbRegionCommittedVersionGcClient {
    private final List<AdbRegionCommittedVersionGcRequest> requests =
        new ArrayList<>();
    private final List<Reply> replies = new ArrayList<>();

    private void complete(String regionId, AdbGcCleanResult result) {
      replies.add(new Reply(regionId,
          CompletableFuture.completedFuture(result)));
    }

    @Override
    public CompletableFuture<AdbGcCleanResult> cleanAsync(
        AdbRegionCommittedVersionGcRequest request) {
      requests.add(request);
      for (Reply reply : replies) {
        if (reply.regionId.equals(request.getRegionId())) {
          return reply.future;
        }
      }
      throw new IllegalStateException("No reply for region "
          + request.getRegionId());
    }
  }

  private static final class Reply {
    private final String regionId;
    private final CompletableFuture<AdbGcCleanResult> future;

    private Reply(String regionId, CompletableFuture<AdbGcCleanResult> future) {
      this.regionId = regionId;
      this.future = future;
    }
  }
}
