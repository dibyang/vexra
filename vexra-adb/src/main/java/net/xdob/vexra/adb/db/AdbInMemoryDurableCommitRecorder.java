package net.xdob.vexra.adb.db;

import java.sql.SQLException;
import java.util.Objects;

/**
 * 基于内存幂等存储的 durable commit 记录器。
 *
 * <p>该实现主要用于 ADB-GA-02 的真实提交路径单元测试和后续持久化实现的语义样板。
 * 它不提供进程重启后的恢复能力，但会严格执行 marker 状态机、幂等冲突检查和已完成状态
 * 的重复推进保护。</p>
 */
public final class AdbInMemoryDurableCommitRecorder
    implements AdbDurableCommitRecorder {
  private final AdbCommitIdempotencyStore store;

  /**
   * 创建内存 durable commit 记录器。
   *
   * @param store commit 幂等 marker 存储
   */
  public AdbInMemoryDurableCommitRecorder(AdbCommitIdempotencyStore store) {
    this.store = Objects.requireNonNull(store, "store == null");
  }

  @Override
  public AdbDurableCommitMarker prewritten(AdbRegionCommitRequest request)
      throws SQLException {
    Objects.requireNonNull(request, "request == null");
    AdbDurableCommitMarker marker = new AdbDurableCommitMarker(
        request.getTxnId(), "", request.getStartTs(), request.getCommitTs(),
        request.getRegionId(), AdbDurableCommitState.PREWRITTEN, "");
    return store.recordOrGet(marker);
  }

  @Override
  public AdbDurableCommitMarker raftCommitted(AdbDurableCommitMarker marker) {
    return advance(marker, AdbDurableCommitState.RAFT_COMMITTED, null);
  }

  @Override
  public AdbDurableCommitMarker storeCommitted(
      AdbDurableCommitMarker marker) {
    return advance(marker, AdbDurableCommitState.STORE_COMMITTED, null);
  }

  @Override
  public AdbDurableCommitMarker replied(AdbDurableCommitMarker marker) {
    return advance(marker, AdbDurableCommitState.REPLIED, null);
  }

  @Override
  public AdbDurableCommitMarker rolledBack(AdbDurableCommitMarker marker,
      Throwable error) {
    String message = error == null || error.getMessage() == null
        ? "" : error.getMessage();
    return advance(marker, AdbDurableCommitState.ROLLED_BACK, message);
  }

  private AdbDurableCommitMarker advance(AdbDurableCommitMarker marker,
      AdbDurableCommitState next, String error) {
    if (marker == null) {
      return null;
    }
    if (isAtOrAfter(marker.getState(), next)) {
      return marker;
    }
    AdbDurableCommitMarker advanced = error == null
        ? marker.transitionTo(next) : marker.transitionTo(next, error);
    store.update(advanced);
    return advanced;
  }

  private static boolean isAtOrAfter(AdbDurableCommitState current,
      AdbDurableCommitState next) {
    if (current == next) {
      return true;
    }
    if (current == AdbDurableCommitState.ROLLED_BACK) {
      return true;
    }
    if (next == AdbDurableCommitState.ROLLED_BACK) {
      return false;
    }
    return rank(current) >= rank(next);
  }

  private static int rank(AdbDurableCommitState state) {
    switch (state) {
      case PREWRITTEN:
        return 1;
      case RAFT_COMMITTED:
        return 2;
      case STORE_COMMITTED:
        return 3;
      case REPLIED:
        return 4;
      case ROLLED_BACK:
      default:
        return 0;
    }
  }
}
