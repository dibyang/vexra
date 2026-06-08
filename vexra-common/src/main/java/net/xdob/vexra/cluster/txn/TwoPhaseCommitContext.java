package net.xdob.vexra.cluster.txn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 两阶段提交上下文。
 *
 * <p>该对象描述一个跨 region 事务的 startTs、commitTs、参与 region 和 2PC 状态。
 * 它是不可变对象，状态迁移会返回新上下文，便于重试和测试。</p>
 */
public final class TwoPhaseCommitContext {
  private final long startTs;
  private final long commitTs;
  private final List<TxnParticipant> participants;
  private final TwoPhaseCommitState state;

  /**
   * 创建两阶段提交上下文。
   *
   * @param startTs 事务开始时间戳
   * @param commitTs 提交时间戳，未提交时为 0
   * @param participants 参与 region
   * @param state 当前 2PC 状态
   */
  public TwoPhaseCommitContext(long startTs, long commitTs,
      List<TxnParticipant> participants, TwoPhaseCommitState state) {
    if (startTs <= 0) {
      throw new IllegalArgumentException("startTs must be positive");
    }
    if (commitTs < 0) {
      throw new IllegalArgumentException("commitTs is negative");
    }
    this.startTs = startTs;
    this.commitTs = commitTs;
    this.participants = immutableParticipants(participants);
    this.state = Objects.requireNonNull(state, "state == null");
    validatePrimary();
  }

  /**
   * 创建新事务上下文。
   *
   * @param startTs 事务开始时间戳
   * @param participants 参与 region
   * @return CREATED 状态上下文
   */
  public static TwoPhaseCommitContext create(long startTs,
      List<TxnParticipant> participants) {
    return new TwoPhaseCommitContext(startTs, 0, participants,
        TwoPhaseCommitState.CREATED);
  }

  public long getStartTs() {
    return startTs;
  }

  public long getCommitTs() {
    return commitTs;
  }

  public List<TxnParticipant> getParticipants() {
    return participants;
  }

  public TwoPhaseCommitState getState() {
    return state;
  }

  /**
   * 进入 prewrite 完成状态。
   *
   * @return PREWRITTEN 状态上下文
   */
  public TwoPhaseCommitContext prewrite() {
    requireState(TwoPhaseCommitState.CREATED, "prewrite");
    return new TwoPhaseCommitContext(startTs, 0, participants,
        TwoPhaseCommitState.PREWRITTEN);
  }

  /**
   * 提交事务。
   *
   * @param newCommitTs 提交时间戳，必须大于 startTs
   * @return COMMITTED 状态上下文
   */
  public TwoPhaseCommitContext commit(long newCommitTs) {
    requireState(TwoPhaseCommitState.PREWRITTEN, "commit");
    if (newCommitTs <= startTs) {
      throw new IllegalArgumentException("commitTs must be greater than startTs");
    }
    return new TwoPhaseCommitContext(startTs, newCommitTs, participants,
        TwoPhaseCommitState.COMMITTED);
  }

  /**
   * 回滚事务。
   *
   * @return ROLLED_BACK 状态上下文
   */
  public TwoPhaseCommitContext rollback() {
    if (state == TwoPhaseCommitState.COMMITTED) {
      throw new IllegalStateException("committed transaction cannot rollback");
    }
    return new TwoPhaseCommitContext(startTs, 0, participants,
        TwoPhaseCommitState.ROLLED_BACK);
  }

  private void requireState(TwoPhaseCommitState expected, String operation) {
    if (state != expected) {
      throw new IllegalStateException(
          operation + " requires state " + expected + ", but was " + state);
    }
  }

  private void validatePrimary() {
    int primaryCount = 0;
    for (TxnParticipant participant : participants) {
      if (participant.isPrimary()) {
        primaryCount++;
      }
    }
    if (primaryCount != 1) {
      throw new IllegalArgumentException(
          "transaction requires exactly one primary participant");
    }
  }

  private static List<TxnParticipant> immutableParticipants(
      List<TxnParticipant> participants) {
    Objects.requireNonNull(participants, "participants == null");
    if (participants.isEmpty()) {
      throw new IllegalArgumentException("participants is empty");
    }
    return Collections.unmodifiableList(new ArrayList<>(participants));
  }
}
