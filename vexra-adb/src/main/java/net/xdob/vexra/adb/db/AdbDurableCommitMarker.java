package net.xdob.vexra.adb.db;

import java.util.Objects;

/**
 * ADB durable commit marker。
 *
 * <p>marker 是 ADB-GA-02 数据安全恢复的最小持久化模型。真实接入 store 后，
 * 每次 commit 都应按 txnId 或 clientRequestId 记录当前提交状态；节点重启时
 * 根据 marker 状态决定丢弃、回滚、前滚或返回已提交结果。</p>
 */
public final class AdbDurableCommitMarker {
  private final long txnId;
  private final String clientRequestId;
  private final long startTs;
  private final long commitTs;
  private final String regionId;
  private final AdbDurableCommitState state;
  private final String lastError;

  /**
   * 创建 durable commit marker。
   *
   * @param txnId ADB 事务 ID
   * @param clientRequestId 客户端幂等 ID，可为空
   * @param startTs 事务开始时间戳
   * @param commitTs 提交时间戳
   * @param regionId 命中的 region ID，MVP 生产路径应只有一个
   * @param state marker 状态
   * @param lastError 最近一次错误，可为空
   */
  public AdbDurableCommitMarker(long txnId, String clientRequestId,
      long startTs, long commitTs, String regionId,
      AdbDurableCommitState state, String lastError) {
    if (txnId < 0) {
      throw new IllegalArgumentException("txnId is negative: " + txnId);
    }
    if (startTs < 0) {
      throw new IllegalArgumentException("startTs is negative: " + startTs);
    }
    if (commitTs <= startTs) {
      throw new IllegalArgumentException(
          "commitTs must be greater than startTs");
    }
    this.txnId = txnId;
    this.clientRequestId = clientRequestId == null
        ? "" : clientRequestId.trim();
    this.startTs = startTs;
    this.commitTs = commitTs;
    this.regionId = normalize(regionId, "regionId");
    this.state = Objects.requireNonNull(state, "state == null");
    this.lastError = lastError == null ? "" : lastError.trim();
  }

  public long getTxnId() {
    return txnId;
  }

  public String getClientRequestId() {
    return clientRequestId;
  }

  public long getStartTs() {
    return startTs;
  }

  public long getCommitTs() {
    return commitTs;
  }

  public String getRegionId() {
    return regionId;
  }

  public AdbDurableCommitState getState() {
    return state;
  }

  public String getLastError() {
    return lastError;
  }

  /**
   * 返回幂等键。
   *
   * @return 优先使用 clientRequestId，否则使用 txnId
   */
  public String idempotencyKey() {
    return clientRequestId.isEmpty() ? "txn:" + txnId
        : "client:" + clientRequestId;
  }

  /**
   * 推进 marker 状态。
   *
   * @param next 下一个状态
   * @return 新 marker
   */
  public AdbDurableCommitMarker transitionTo(AdbDurableCommitState next) {
    return transitionTo(next, "");
  }

  /**
   * 推进 marker 状态并记录错误。
   *
   * @param next 下一个状态
   * @param error 错误说明
   * @return 新 marker
   */
  public AdbDurableCommitMarker transitionTo(AdbDurableCommitState next,
      String error) {
    Objects.requireNonNull(next, "next == null");
    if (next == state) {
      return new AdbDurableCommitMarker(txnId, clientRequestId, startTs,
          commitTs, regionId, state, error);
    }
    if (!canTransition(state, next)) {
      throw new IllegalStateException("Illegal ADB durable commit transition: "
          + state + " -> " + next);
    }
    return new AdbDurableCommitMarker(txnId, clientRequestId, startTs,
        commitTs, regionId, next, error);
  }

  private static boolean canTransition(AdbDurableCommitState current,
      AdbDurableCommitState next) {
    switch (current) {
      case PREWRITTEN:
        return next == AdbDurableCommitState.RAFT_COMMITTED
            || next == AdbDurableCommitState.ROLLED_BACK;
      case RAFT_COMMITTED:
        return next == AdbDurableCommitState.STORE_COMMITTED;
      case STORE_COMMITTED:
        return next == AdbDurableCommitState.REPLIED;
      case REPLIED:
      case ROLLED_BACK:
      default:
        return false;
    }
  }

  private static String normalize(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return value.trim();
  }
}
