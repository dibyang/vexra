package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.cluster.region.KeyRange;
import net.xdob.vexra.cluster.region.RegionMetadata;
import net.xdob.vexra.ha.ReplicaRole;
import net.xdob.vexra.ha.VirtualNodeMetadata;
import net.xdob.vexra.ha.VirtualNodeReplica;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADB 集群级 committed version GC 调度器测试。
 *
 * <p>验证调度器按照控制面 region 快照生成分片 GC 请求，并对无 leader、失败和
 * 超时给出明确的调度语义。真实 region-scoped 删除由后续 cleaner 增量承接。</p>
 */
class AdbClusterCommittedVersionGcSchedulerTest {

  /**
   * 验证调度器会为每个有 leader 的 region 派发请求，并聚合成功结果。
   */
  @Test
  void shouldDispatchRegionGcRequestsAndAggregateResults() throws Exception {
    RecordingGcClient client = new RecordingGcClient();
    client.complete("r1", new AdbGcCleanResult(3, 1));
    client.complete("r2", new AdbGcCleanResult(5, 2));
    AdbClusterCommittedVersionGcScheduler scheduler = scheduler(
        snapshot(7, region("r1", 1, 100, "node-a"),
            region("r2", 100, 200, "node-b")), 25, client);

    AdbClusterCommittedVersionGcResult result =
        scheduler.cleanOnce(4, 1000);

    assertEquals(7, result.getRouteEpoch());
    assertEquals(2, result.getScheduledRegions());
    assertEquals(2, result.getCompletedRegions());
    assertEquals(0, result.getSkippedNoLeaderRegions());
    assertEquals(8, result.getScannedVersions());
    assertEquals(3, result.getDeletedVersions());
    assertEquals(Arrays.asList("r1", "r2"), client.requestRegionIds());

    AdbRegionCommittedVersionGcRequest first = client.requests.get(0);
    assertEquals("node-a", first.getLeaderId());
    assertEquals(7, first.getRouteEpoch());
    assertEquals(25, first.getSafePoint());
    assertEquals(4, first.getLimit());
    assertEquals(1000, first.getTimeoutMillis());
    assertArrayEquals(rowKey(1).toBytes(), first.getRange().getStartKey());
    assertArrayEquals(rowKey(100).toBytes(), first.getRange().getEndKey());
  }

  /**
   * 验证无 leader 的 region 会被跳过，不会派发到 client。
   */
  @Test
  void shouldSkipRegionsWithoutLeader() throws Exception {
    RecordingGcClient client = new RecordingGcClient();
    client.complete("r2", new AdbGcCleanResult(1, 0));
    AdbClusterCommittedVersionGcScheduler scheduler = scheduler(
        snapshot(8, region("r1", 1, 100, ""),
            region("r2", 100, 200, "node-b")), 30, client);

    AdbClusterCommittedVersionGcResult result =
        scheduler.cleanOnce(0, 1000);

    assertEquals(Collections.singletonList("r2"), client.requestRegionIds());
    assertEquals(1, result.getScheduledRegions());
    assertEquals(1, result.getCompletedRegions());
    assertEquals(1, result.getSkippedNoLeaderRegions());
  }

  /**
   * 验证 region 执行失败会映射为带 regionId 的 SQLException。
   */
  @Test
  void shouldMapRegionFailureToSQLException() {
    RecordingGcClient client = new RecordingGcClient();
    client.complete("r1", new AdbGcCleanResult(1, 0));
    SQLException cause = new SQLException("disk failed");
    client.fail("r2", cause);
    AdbClusterCommittedVersionGcScheduler scheduler = scheduler(
        snapshot(9, region("r1", 1, 100, "node-a"),
            region("r2", 100, 200, "node-b")), 30, client);

    SQLException error = assertThrows(SQLException.class,
        () -> scheduler.cleanOnce(0, 1000));

    assertTrue(error.getMessage().contains("regionId=r2"));
    assertSame(cause, error.getCause());
  }

  /**
   * 验证等待超时时会取消未完成的 region future 并返回明确错误。
   */
  @Test
  void shouldCancelPendingRegionWhenTimeout() {
    RecordingGcClient client = new RecordingGcClient();
    CompletableFuture<AdbGcCleanResult> pending = new CompletableFuture<>();
    client.pending("r1", pending);
    AdbClusterCommittedVersionGcScheduler scheduler = scheduler(
        snapshot(10, region("r1", 1, 100, "node-a")), 30, client);

    SQLException error = assertThrows(SQLException.class,
        () -> scheduler.cleanOnce(0, 1));

    assertTrue(error.getMessage().contains("Timed out"));
    assertTrue(error.getMessage().contains("regionId=r1"));
    assertTrue(pending.isCancelled());
  }

  private static AdbClusterCommittedVersionGcScheduler scheduler(
      AdbControlPlaneSnapshot snapshot, long safePoint,
      RecordingGcClient client) {
    return new AdbClusterCommittedVersionGcScheduler(() -> snapshot,
        () -> safePoint, client);
  }

  private static AdbControlPlaneSnapshot snapshot(long routeEpoch,
      RegionMetadata... regions) {
    return new AdbControlPlaneSnapshot(routeEpoch, Arrays.asList(regions));
  }

  private static RegionMetadata region(String regionId, long startRow,
      long endRow, String leaderId) {
    return new RegionMetadata(regionId,
        new KeyRange(rowKey(startRow).toBytes(), rowKey(endRow).toBytes()), 1,
        new VirtualNodeMetadata("vn-" + regionId, 1, leaderId,
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

    private void fail(String regionId, SQLException error) {
      CompletableFuture<AdbGcCleanResult> future = new CompletableFuture<>();
      future.completeExceptionally(error);
      replies.add(new Reply(regionId, future));
    }

    private void pending(String regionId,
        CompletableFuture<AdbGcCleanResult> future) {
      replies.add(new Reply(regionId, future));
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

    private List<String> requestRegionIds() {
      List<String> ids = new ArrayList<>();
      for (AdbRegionCommittedVersionGcRequest request : requests) {
        ids.add(request.getRegionId());
      }
      return ids;
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
