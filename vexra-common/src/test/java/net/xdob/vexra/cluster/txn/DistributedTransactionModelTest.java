package net.xdob.vexra.cluster.txn;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分布式事务最小闭环回归测试。
 *
 * <p>测试覆盖 ADB-Cluster-04 的公共模型：TSO 单调递增、2PC 状态迁移、
 * commitTs 约束、rollback 约束和 lock resolve 超时判断。</p>
 */
class DistributedTransactionModelTest {
  /**
   * 验证 TSO 返回值严格递增。
   */
  @Test
  void shouldGenerateMonotonicTimestamps() {
    TimestampOracle tso = new InMemoryTimestampOracle(100);

    assertEquals(101, tso.nextTimestamp());
    assertEquals(102, tso.nextTimestamp());
  }

  /**
   * 验证 2PC 正常 prewrite 和 commit 路径。
   */
  @Test
  void shouldCommitAfterPrewrite() {
    TwoPhaseCommitContext created = TwoPhaseCommitContext.create(10,
        Arrays.asList(
            new TxnParticipant("r1", true),
            new TxnParticipant("r2", false)));

    TwoPhaseCommitContext prewritten = created.prewrite();
    TwoPhaseCommitContext committed = prewritten.commit(12);

    assertEquals(TwoPhaseCommitState.PREWRITTEN, prewritten.getState());
    assertEquals(TwoPhaseCommitState.COMMITTED, committed.getState());
    assertEquals(12, committed.getCommitTs());
  }

  /**
   * 验证未 prewrite 不允许 commit，已 commit 不允许 rollback。
   */
  @Test
  void shouldRejectInvalidTwoPhaseTransitions() {
    TwoPhaseCommitContext created = TwoPhaseCommitContext.create(10,
        Arrays.asList(new TxnParticipant("r1", true)));

    assertThrows(IllegalStateException.class, () -> created.commit(11));
    assertThrows(IllegalArgumentException.class,
        () -> created.prewrite().commit(10));
    assertThrows(IllegalStateException.class,
        () -> created.prewrite().commit(11).rollback());
  }

  /**
   * 验证事务必须恰好有一个 primary participant。
   */
  @Test
  void shouldRequireExactlyOnePrimaryParticipant() {
    assertThrows(IllegalArgumentException.class,
        () -> TwoPhaseCommitContext.create(10,
            Arrays.asList(new TxnParticipant("r1", false))));
    assertThrows(IllegalArgumentException.class,
        () -> TwoPhaseCommitContext.create(10,
            Arrays.asList(
                new TxnParticipant("r1", true),
                new TxnParticipant("r2", true))));
  }

  /**
   * 验证 lock resolve 可根据 TTL 判断锁是否过期。
   */
  @Test
  void shouldDetectExpiredTxnLock() {
    TxnLock lock = new TxnLock(bytes("k1"), bytes("pk"), 100,
        "r1", 10);

    assertFalse(lock.isExpired(109));
    assertTrue(lock.isExpired(111));
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
