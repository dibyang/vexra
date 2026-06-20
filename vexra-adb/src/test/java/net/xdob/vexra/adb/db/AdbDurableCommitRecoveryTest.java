package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.adb.ldb.LdbStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADB-GA-02 durable commit marker 和恢复决策测试。
 */
class AdbDurableCommitRecoveryTest {
  @TempDir
  File tempDir;

  /**
   * 验证 durable commit marker 只能按数据安全状态机前进。
   */
  @Test
  void shouldAdvanceDurableCommitMarkerInSafeOrder() {
    AdbDurableCommitMarker prewritten = marker(1,
        AdbDurableCommitState.PREWRITTEN);
    AdbDurableCommitMarker raftCommitted = prewritten.transitionTo(
        AdbDurableCommitState.RAFT_COMMITTED);
    AdbDurableCommitMarker storeCommitted = raftCommitted.transitionTo(
        AdbDurableCommitState.STORE_COMMITTED);
    AdbDurableCommitMarker replied = storeCommitted.transitionTo(
        AdbDurableCommitState.REPLIED);

    assertEquals(AdbDurableCommitState.RAFT_COMMITTED,
        raftCommitted.getState());
    assertEquals(AdbDurableCommitState.STORE_COMMITTED,
        storeCommitted.getState());
    assertTrue(replied.getState().isTerminal());

    assertThrows(IllegalStateException.class,
        () -> raftCommitted.transitionTo(AdbDurableCommitState.ROLLED_BACK));
    assertThrows(IllegalStateException.class,
        () -> storeCommitted.transitionTo(AdbDurableCommitState.ROLLED_BACK));
  }

  /**
   * 验证 prewrite 后尚未提交的 marker 可以回滚。
   */
  @Test
  void shouldAllowRollbackBeforeRaftCommit() {
    AdbDurableCommitMarker rolledBack = marker(2,
        AdbDurableCommitState.PREWRITTEN).transitionTo(
        AdbDurableCommitState.ROLLED_BACK, "primary timeout");

    assertEquals(AdbDurableCommitState.ROLLED_BACK, rolledBack.getState());
    assertTrue(rolledBack.getLastError().contains("primary timeout"));
  }

  /**
   * 验证恢复扫描把不同 marker 状态映射为安全恢复动作。
   */
  @Test
  void shouldMapMarkersToRecoveryActions() {
    List<AdbCommitRecoveryDecision> decisions =
        new AdbCommitRecoveryScanner().scan(Arrays.asList(
            marker(10, AdbDurableCommitState.PREWRITTEN),
            marker(11, AdbDurableCommitState.RAFT_COMMITTED),
            marker(12, AdbDurableCommitState.STORE_COMMITTED),
            marker(13, AdbDurableCommitState.REPLIED),
            marker(14, AdbDurableCommitState.ROLLED_BACK)));

    assertEquals(AdbCommitRecoveryAction.ROLLBACK,
        decisions.get(0).getAction());
    assertEquals(AdbCommitRecoveryAction.ROLL_FORWARD,
        decisions.get(1).getAction());
    assertEquals(AdbCommitRecoveryAction.RETURN_COMMITTED,
        decisions.get(2).getAction());
    assertEquals(AdbCommitRecoveryAction.RETURN_COMMITTED,
        decisions.get(3).getAction());
    assertEquals(AdbCommitRecoveryAction.DISCARD,
        decisions.get(4).getAction());
  }

  /**
   * 验证同一客户端幂等键重复提交不会生成新的 commitTs。
   */
  @Test
  void shouldDeduplicateCommitByClientRequestId() throws Exception {
    AdbCommitIdempotencyStore store = new AdbCommitIdempotencyStore();
    AdbDurableCommitMarker first = new AdbDurableCommitMarker(100,
        "client-call-1", 10, 20, "r1",
        AdbDurableCommitState.STORE_COMMITTED, "");
    AdbDurableCommitMarker duplicate = new AdbDurableCommitMarker(100,
        "client-call-1", 10, 20, "r1",
        AdbDurableCommitState.REPLIED, "");

    assertSame(first, store.recordOrGet(first));
    assertSame(first, store.recordOrGet(duplicate));
    assertEquals(1, store.snapshot().size());
  }

  /**
   * 验证同一幂等键映射到不同 commitTs 时会失败，避免重复应用。
   */
  @Test
  void shouldRejectConflictingIdempotencyKey() throws Exception {
    AdbCommitIdempotencyStore store = new AdbCommitIdempotencyStore();
    store.recordOrGet(new AdbDurableCommitMarker(100, "client-call-2",
        10, 20, "r1", AdbDurableCommitState.STORE_COMMITTED, ""));

    SQLException error = assertThrows(SQLException.class,
        () -> store.recordOrGet(new AdbDurableCommitMarker(100,
            "client-call-2", 10, 21, "r1",
            AdbDurableCommitState.STORE_COMMITTED, "")));
    assertTrue(error.getMessage().contains("idempotency key conflict"));
  }

  /**
   * 验证持久化 recorder 能在 store reopen 后保留 in-doubt marker，并生成前滚恢复动作。
   */
  @Test
  void shouldScanPersistentMarkerAfterReopen() throws Exception {
    File dbDir = new File(tempDir, "persistent-marker");
    try (LdbStore store = new LdbStore(dbDir.getAbsolutePath())) {
      AdbPersistentDurableCommitRecorder recorder =
          new AdbPersistentDurableCommitRecorder(store);
      AdbDurableCommitMarker marker = recorder.prewritten(request("r1"));
      recorder.raftCommitted(marker);
    }

    try (LdbStore reopened = new LdbStore(dbDir.getAbsolutePath())) {
      AdbPersistentDurableCommitRecorder recorder =
          new AdbPersistentDurableCommitRecorder(reopened);
      Collection<AdbDurableCommitMarker> markers = recorder.snapshot();
      List<AdbCommitRecoveryDecision> decisions =
          new AdbCommitRecoveryScanner().scan(markers);

      assertEquals(1, decisions.size());
      assertEquals("r1", decisions.get(0).getMarker().getRegionId());
      assertEquals(AdbDurableCommitState.RAFT_COMMITTED,
          decisions.get(0).getMarker().getState());
      assertEquals(AdbCommitRecoveryAction.ROLL_FORWARD,
          decisions.get(0).getAction());
    }
  }

  /**
   * 验证持久化 recorder 对重复提交保持同一 marker，不会降级已完成状态。
   */
  @Test
  void shouldKeepPersistentMarkerIdempotent() throws Exception {
    File dbDir = new File(tempDir, "persistent-idempotent");
    try (LdbStore store = new LdbStore(dbDir.getAbsolutePath())) {
      AdbPersistentDurableCommitRecorder recorder =
          new AdbPersistentDurableCommitRecorder(store);
      AdbDurableCommitMarker marker = recorder.prewritten(request("r1"));
      marker = recorder.raftCommitted(marker);
      marker = recorder.storeCommitted(marker);
      recorder.replied(marker);

      AdbDurableCommitMarker duplicate = recorder.prewritten(request("r1"));
      assertEquals(AdbDurableCommitState.REPLIED, duplicate.getState());
      assertEquals(1, recorder.snapshot().size());
    }
  }

  /**
   * 验证恢复执行器能根据 marker 决策执行 rollback、roll-forward 和 return committed。
   */
  @Test
  void shouldExecuteRecoveryDecisionsAgainstStore() throws Exception {
    RecordingStore store = new RecordingStore();
    AdbCommitIdempotencyStore markerStore = new AdbCommitIdempotencyStore();
    AdbInMemoryDurableCommitRecorder recorder =
        new AdbInMemoryDurableCommitRecorder(markerStore);
    AdbDurableCommitMarker prewritten = marker(201,
        AdbDurableCommitState.PREWRITTEN);
    AdbDurableCommitMarker raftCommitted = marker(202,
        AdbDurableCommitState.RAFT_COMMITTED);
    AdbDurableCommitMarker storeCommitted = marker(203,
        AdbDurableCommitState.STORE_COMMITTED);
    markerStore.update(prewritten);
    markerStore.update(raftCommitted);
    markerStore.update(storeCommitted);

    List<AdbCommitRecoveryDecision> decisions =
        new AdbCommitRecoveryScanner().scan(markerStore.snapshot());
    AdbCommitRecoveryResult result = new AdbCommitRecoveryExecutor(store,
        recorder).recover(decisions);

    assertEquals(3, result.getScanned());
    assertEquals(1, result.getRolledBack());
    assertEquals(1, result.getRolledForward());
    assertEquals(1, result.getReturnedCommitted());
    assertEquals(Collections.singletonList(201L), store.rollbacks);
    assertEquals(Collections.singletonList(202L), store.commits);
    assertEquals(AdbDurableCommitState.ROLLED_BACK,
        marker(markerStore.snapshot(), "r1", 201).getState());
    assertEquals(AdbDurableCommitState.REPLIED,
        marker(markerStore.snapshot(), "r1", 202).getState());
    assertEquals(AdbDurableCommitState.REPLIED,
        marker(markerStore.snapshot(), "r1", 203).getState());
  }

  /**
   * 验证 reopen 后扫描到的 RAFT_COMMITTED marker 可以被执行器前滚并更新为 REPLIED。
   */
  @Test
  void shouldRecoverPersistentMarkerAfterReopen() throws Exception {
    File dbDir = new File(tempDir, "persistent-recovery");
    try (LdbStore store = new LdbStore(dbDir.getAbsolutePath())) {
      AdbPersistentDurableCommitRecorder recorder =
          new AdbPersistentDurableCommitRecorder(store);
      AdbDurableCommitMarker marker = recorder.prewritten(request("r1"));
      recorder.raftCommitted(marker);
    }

    try (LdbStore reopened = new LdbStore(dbDir.getAbsolutePath())) {
      AdbPersistentDurableCommitRecorder recorder =
          new AdbPersistentDurableCommitRecorder(reopened);
      List<AdbCommitRecoveryDecision> decisions =
          new AdbCommitRecoveryScanner().scan(recorder.snapshot());
      AdbCommitRecoveryResult result = new AdbCommitRecoveryExecutor(reopened,
          recorder).recover(decisions);

      assertEquals(1, result.getRolledForward());
      Collection<AdbDurableCommitMarker> markers = recorder.snapshot();
      assertEquals(AdbDurableCommitState.REPLIED,
          marker(markers, "r1", 100).getState());
    }
  }

  /**
   * 验证启动恢复服务可以同步扫描持久化 marker 并执行前滚。
   */
  @Test
  void shouldRecoverPersistentMarkerThroughStartupService() throws Exception {
    File dbDir = new File(tempDir, "startup-service");
    try (LdbStore store = new LdbStore(dbDir.getAbsolutePath())) {
      AdbPersistentDurableCommitRecorder recorder =
          new AdbPersistentDurableCommitRecorder(store);
      AdbDurableCommitMarker marker = recorder.prewritten(request("r1"));
      recorder.raftCommitted(marker);
    }

    try (LdbStore reopened = new LdbStore(dbDir.getAbsolutePath())) {
      AdbCommitRecoveryResult result =
          new AdbStartupRecoveryService(reopened).recoverOnce();
      AdbPersistentDurableCommitRecorder recorder =
          new AdbPersistentDurableCommitRecorder(reopened);

      assertEquals(1, result.getRolledForward());
      assertEquals(AdbDurableCommitState.REPLIED,
          marker(recorder.snapshot(), "r1", 100).getState());
    }
  }

  private static AdbDurableCommitMarker marker(long txnId,
      AdbDurableCommitState state) {
    return new AdbDurableCommitMarker(txnId, "client-" + txnId,
        100, 200, "r1", state, "");
  }

  private static AdbDurableCommitMarker marker(
      Collection<AdbDurableCommitMarker> markers, String regionId,
      long txnId) {
    for (AdbDurableCommitMarker marker : markers) {
      if (marker.getTxnId() == txnId
          && marker.getRegionId().equals(regionId)) {
        return marker;
      }
    }
    throw new AssertionError("missing marker, txnId=" + txnId
        + ", regionId=" + regionId);
  }

  private static AdbRegionCommitRequest request(String regionId) {
    return new AdbRegionCommitRequest(regionId, 1, "node-a", 100,
        10, 20, regionId, RowKey.of(TabId.of(1, 0L), 1), 3000,
        true, Collections.singletonList(RowKey.of(TabId.of(1, 0L), 1)),
        Collections.emptyList());
  }

  private static final class RecordingStore implements DbStore {
    private final List<Long> commits = new ArrayList<>();
    private final List<Long> rollbacks = new ArrayList<>();

    @Override
    public byte[] get(byte[] key) {
      return null;
    }

    @Override
    public void put(byte[] key, byte[] value) {
    }

    @Override
    public long addLong(byte[] key, long operand) {
      return 0;
    }

    @Override
    public Optional<Long> getLong(byte[] key) {
      return Optional.empty();
    }

    @Override
    public void putLong(byte[] key, long value) {
    }

    @Override
    public void delete(byte[] key) {
    }

    @Override
    public void deleteRange(byte[] startKey, byte[] endKey) {
    }

    @Override
    public byte[] get(byte cfId, byte[] key) {
      return null;
    }

    @Override
    public void put(byte cfId, byte[] key, byte[] value) {
    }

    @Override
    public long addLong(byte cfId, byte[] key, long delta) {
      return 0;
    }

    @Override
    public Optional<Long> getLong(byte cfId, byte[] key) {
      return Optional.empty();
    }

    @Override
    public void putLong(byte cfId, byte[] key, long value) {
    }

    @Override
    public void delete(byte cfId, byte[] key) {
    }

    @Override
    public void deleteRange(byte cfId, byte[] startKey, byte[] endKey) {
    }

    @Override
    public void checkpoint(String targetDir) {
    }

    @Override
    public void restore(String sourceDir) {
    }

    @Override
    public void writeBatch(WriteBatchConsumer consumer) {
    }

    @Override
    public void rollback(long txnId) {
      rollbacks.add(txnId);
    }

    @Override
    public CompletableFuture<Void> commitAsync(long txnId, long commitTs,
        List<Meta> metas) {
      commits.add(txnId);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> rollbackAsync(long txnId) {
      rollbacks.add(txnId);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public VersionScanSource openVersionScanSource(ScanDirection direction) {
      throw new UnsupportedOperationException();
    }

    @Override
    public VersionScanSource openVersionScanSource(byte cfId,
        ScanDirection direction) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() throws IOException {
    }
  }
}
