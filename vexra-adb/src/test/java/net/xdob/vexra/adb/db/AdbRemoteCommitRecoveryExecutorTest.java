package net.xdob.vexra.adb.db;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ADB remote commit marker 恢复执行器测试。
 *
 * <p>测试覆盖 remote Raft region 恢复边界：执行器必须通过
 * {@link AdbRegionCommitClient} 进行 rollback / commit 前滚，而不是直接访问本地
 * store。marker 状态仍沿用 GA-02 的 durable commit 状态机。</p>
 */
class AdbRemoteCommitRecoveryExecutorTest {

  /**
   * 验证 remote 恢复执行器会调用 region commit client 并推进 marker 状态。
   */
  @Test
  void shouldRecoverRemoteMarkersThroughRegionCommitClient()
      throws Exception {
    RecordingCommitClient client = new RecordingCommitClient();
    AdbCommitIdempotencyStore markerStore = new AdbCommitIdempotencyStore();
    AdbInMemoryDurableCommitRecorder recorder =
        new AdbInMemoryDurableCommitRecorder(markerStore);
    markerStore.update(marker(1, "r1", AdbDurableCommitState.PREWRITTEN));
    markerStore.update(marker(2, "r2",
        AdbDurableCommitState.RAFT_COMMITTED));
    markerStore.update(marker(3, "r3",
        AdbDurableCommitState.STORE_COMMITTED));

    List<AdbCommitRecoveryDecision> decisions =
        new AdbCommitRecoveryScanner().scan(markerStore.snapshot());
    AdbCommitRecoveryResult result =
        new AdbRemoteCommitRecoveryExecutor(client, recorder)
            .recover(decisions);

    assertEquals(3, result.getScanned());
    assertEquals(1, result.getRolledBack());
    assertEquals(1, result.getRolledForward());
    assertEquals(1, result.getReturnedCommitted());
    assertEquals(Collections.singletonList("r1"),
        regionIds(client.rollbacks));
    assertEquals(Collections.singletonList("r2"),
        regionIds(client.commits));
    assertEquals(AdbDurableCommitState.ROLLED_BACK,
        marker(markerStore.snapshot(), 1, "r1").getState());
    assertEquals(AdbDurableCommitState.REPLIED,
        marker(markerStore.snapshot(), 2, "r2").getState());
    assertEquals(AdbDurableCommitState.REPLIED,
        marker(markerStore.snapshot(), 3, "r3").getState());
  }

  private static AdbDurableCommitMarker marker(long txnId, String regionId,
      AdbDurableCommitState state) {
    return new AdbDurableCommitMarker(txnId, "", 10, 20, regionId, state,
        "");
  }

  private static AdbDurableCommitMarker marker(
      Iterable<AdbDurableCommitMarker> markers, long txnId, String regionId) {
    for (AdbDurableCommitMarker marker : markers) {
      if (marker.getTxnId() == txnId
          && marker.getRegionId().equals(regionId)) {
        return marker;
      }
    }
    throw new AssertionError("missing marker, txnId=" + txnId
        + ", regionId=" + regionId);
  }

  private static List<String> regionIds(List<AdbRegionCommitRequest> requests) {
    List<String> ids = new ArrayList<>();
    for (AdbRegionCommitRequest request : requests) {
      ids.add(request.getRegionId());
    }
    return ids;
  }

  private static final class RecordingCommitClient
      implements AdbRegionCommitClient {
    private final List<AdbRegionCommitRequest> commits = new ArrayList<>();
    private final List<AdbRegionCommitRequest> rollbacks = new ArrayList<>();

    @Override
    public CompletableFuture<Void> commitAsync(AdbRegionCommitRequest request) {
      commits.add(request);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> rollbackAsync(
        AdbRegionCommitRequest request) {
      rollbacks.add(request);
      return CompletableFuture.completedFuture(null);
    }
  }
}
