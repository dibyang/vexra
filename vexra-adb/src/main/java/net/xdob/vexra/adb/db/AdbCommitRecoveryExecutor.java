package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.DbStore;

import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.CompletionException;

/**
 * ADB durable commit 恢复执行器。
 *
 * <p>该执行器把 {@link AdbCommitRecoveryScanner} 生成的恢复决策落到真实 store
 * 操作上：PREWRITTEN 回滚，RAFT_COMMITTED 前滚，STORE_COMMITTED/REPLIED
 * 返回已提交语义。它不扫描 marker，也不决定恢复动作，只负责编排可幂等执行的恢复步骤
 * 并通过 {@link AdbDurableCommitRecorder} 更新 marker。</p>
 */
public final class AdbCommitRecoveryExecutor {
  private final DbStore store;
  private final AdbDurableCommitRecorder recorder;

  /**
   * 创建 durable commit 恢复执行器。
   *
   * @param store ADB 底层 store
   * @param recorder durable commit marker 记录器
   */
  public AdbCommitRecoveryExecutor(DbStore store,
      AdbDurableCommitRecorder recorder) {
    this.store = Objects.requireNonNull(store, "store == null");
    this.recorder = Objects.requireNonNull(recorder, "recorder == null");
  }

  /**
   * 执行一组恢复决策。
   *
   * @param decisions 恢复扫描输出的决策
   * @return 本轮恢复执行结果
   * @throws SQLException store 操作或 marker 更新失败时抛出
   */
  public AdbCommitRecoveryResult recover(
      Collection<AdbCommitRecoveryDecision> decisions) throws SQLException {
    Objects.requireNonNull(decisions, "decisions == null");
    int rolledBack = 0;
    int rolledForward = 0;
    int returnedCommitted = 0;
    int discarded = 0;
    for (AdbCommitRecoveryDecision decision : decisions) {
      switch (decision.getAction()) {
        case ROLLBACK:
          rollback(decision.getMarker());
          rolledBack++;
          break;
        case ROLL_FORWARD:
          rollForward(decision.getMarker());
          rolledForward++;
          break;
        case RETURN_COMMITTED:
          returnCommitted(decision.getMarker());
          returnedCommitted++;
          break;
        case DISCARD:
        default:
          discarded++;
          break;
      }
    }
    return new AdbCommitRecoveryResult(decisions.size(), rolledBack,
        rolledForward, returnedCommitted, discarded);
  }

  private void rollback(AdbDurableCommitMarker marker) throws SQLException {
    try {
      store.rollbackAsync(marker.getTxnId()).join();
      recorder.rolledBack(marker, null);
    } catch (CompletionException e) {
      throw commitRecoveryError("rollback", marker, unwrap(e));
    } catch (RuntimeException e) {
      throw commitRecoveryError("rollback", marker, e);
    }
  }

  private void rollForward(AdbDurableCommitMarker marker) throws SQLException {
    try {
      store.commitAsync(marker.getTxnId(), marker.getCommitTs(),
          Collections.emptyList()).join();
      AdbDurableCommitMarker storeCommitted =
          recorder.storeCommitted(marker);
      recorder.replied(storeCommitted);
    } catch (CompletionException e) {
      throw commitRecoveryError("roll forward", marker, unwrap(e));
    } catch (RuntimeException e) {
      throw commitRecoveryError("roll forward", marker, e);
    }
  }

  private void returnCommitted(AdbDurableCommitMarker marker)
      throws SQLException {
    recorder.replied(marker);
  }

  private static SQLException commitRecoveryError(String action,
      AdbDurableCommitMarker marker, Throwable cause) {
    if (cause instanceof SQLException) {
      return (SQLException) cause;
    }
    return new SQLException("Failed to " + action
        + " ADB durable commit marker, txnId=" + marker.getTxnId()
        + ", regionId=" + marker.getRegionId()
        + ", state=" + marker.getState(), cause);
  }

  private static Throwable unwrap(Throwable error) {
    Throwable current = error;
    while (current instanceof CompletionException
        && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }
}
