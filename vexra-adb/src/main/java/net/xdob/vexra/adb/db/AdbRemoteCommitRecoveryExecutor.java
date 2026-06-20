package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.DataKey;
import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.TabId;

import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.CompletionException;

/**
 * ADB remote region durable commit 恢复执行器。
 *
 * <p>该执行器用于 remote Raft region marker 的恢复。它不直接访问本地
 * {@link net.xdob.vexra.adb.DbStore}，而是复用 {@link AdbRegionCommitClient}
 * 对远端 region 执行 rollback 或 commit 前滚，再通过 recorder 推进 marker。
 * 当前 marker 尚未保存完整 write set，因此 roll-forward 只携带事务 ID、commitTs 和
 * 空 metas，适用于已经由 remote region durable prewrite 保存 intent 的恢复场景。</p>
 */
public final class AdbRemoteCommitRecoveryExecutor {
  private static final DataKey PLACEHOLDER_KEY =
      RowKey.of(TabId.of(Integer.MAX_VALUE, 0L), 0L);

  private final AdbRegionCommitClient client;
  private final AdbDurableCommitRecorder recorder;

  /**
   * 创建 remote commit 恢复执行器。
   *
   * @param client remote region commit client
   * @param recorder durable commit marker 记录器
   */
  public AdbRemoteCommitRecoveryExecutor(AdbRegionCommitClient client,
      AdbDurableCommitRecorder recorder) {
    this.client = Objects.requireNonNull(client, "client == null");
    this.recorder = Objects.requireNonNull(recorder, "recorder == null");
  }

  /**
   * 执行一组 remote region 恢复决策。
   *
   * @param decisions 恢复扫描输出的决策
   * @return 本轮恢复执行统计
   * @throws SQLException remote commit client 或 marker 更新失败时抛出
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
          recorder.replied(decision.getMarker());
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
      client.rollbackAsync(request(marker)).join();
      recorder.rolledBack(marker, null);
    } catch (CompletionException e) {
      throw remoteRecoveryError("rollback", marker, unwrap(e));
    } catch (RuntimeException e) {
      throw remoteRecoveryError("rollback", marker, e);
    }
  }

  private void rollForward(AdbDurableCommitMarker marker) throws SQLException {
    try {
      client.commitAsync(request(marker)).join();
      AdbDurableCommitMarker storeCommitted =
          recorder.storeCommitted(marker);
      recorder.replied(storeCommitted);
    } catch (CompletionException e) {
      throw remoteRecoveryError("roll forward", marker, unwrap(e));
    } catch (RuntimeException e) {
      throw remoteRecoveryError("roll forward", marker, e);
    }
  }

  private static AdbRegionCommitRequest request(
      AdbDurableCommitMarker marker) {
    return new AdbRegionCommitRequest(marker.getRegionId(), 0,
        "recovery", marker.getTxnId(), marker.getStartTs(),
        marker.getCommitTs(), marker.getRegionId(), PLACEHOLDER_KEY,
        0, true, Collections.singletonList(PLACEHOLDER_KEY),
        Collections.emptyList(), Collections.emptyList());
  }

  private static SQLException remoteRecoveryError(String action,
      AdbDurableCommitMarker marker, Throwable cause) {
    if (cause instanceof SQLException) {
      return (SQLException) cause;
    }
    return new SQLException("Failed to " + action
        + " remote ADB durable commit marker, txnId=" + marker.getTxnId()
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
