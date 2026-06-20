package net.xdob.vexra.adb.db;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADB-GA-02 durable commit marker 和恢复决策测试。
 */
class AdbDurableCommitRecoveryTest {

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

  private static AdbDurableCommitMarker marker(long txnId,
      AdbDurableCommitState state) {
    return new AdbDurableCommitMarker(txnId, "client-" + txnId,
        100, 200, "r1", state, "");
  }
}
