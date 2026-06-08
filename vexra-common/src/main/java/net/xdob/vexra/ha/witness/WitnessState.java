package net.xdob.vexra.ha.witness;

/**
 * Witness 最小仲裁状态。
 *
 * <p>Witness 不保存业务数据，但必须持久化 term、votedFor、epoch、commitIndex
 * 和可选 lease 信息，避免重启后重复投票或接受过期元数据。</p>
 */
public final class WitnessState {
  private final String virtualNodeId;
  private final long currentTerm;
  private final String votedFor;
  private final long acceptedEpoch;
  private final long commitIndex;
  private final String leaseOwner;
  private final long leaseExpireAtMillis;

  /**
   * 创建 witness 仲裁状态。
   *
   * @param virtualNodeId 所属虚节点标识
   * @param currentTerm 当前任期
   * @param votedFor 当前任期已投票对象，可为空
   * @param acceptedEpoch 已接受的元数据 epoch
   * @param commitIndex 已观察到的提交位置
   * @param leaseOwner 可选 lease 持有者
   * @param leaseExpireAtMillis 可选 lease 过期时间，0 表示未启用
   */
  public WitnessState(String virtualNodeId, long currentTerm, String votedFor,
      long acceptedEpoch, long commitIndex, String leaseOwner,
      long leaseExpireAtMillis) {
    this.virtualNodeId = normalizeRequired(virtualNodeId, "virtualNodeId");
    this.currentTerm = nonNegative(currentTerm, "currentTerm");
    this.votedFor = normalizeOptional(votedFor);
    this.acceptedEpoch = nonNegative(acceptedEpoch, "acceptedEpoch");
    this.commitIndex = nonNegative(commitIndex, "commitIndex");
    this.leaseOwner = normalizeOptional(leaseOwner);
    this.leaseExpireAtMillis = nonNegative(leaseExpireAtMillis, "leaseExpireAtMillis");
  }

  /**
   * 创建指定虚节点的空 witness 状态。
   *
   * @param virtualNodeId 虚节点标识
   * @return 初始状态
   */
  public static WitnessState empty(String virtualNodeId) {
    return new WitnessState(virtualNodeId, 0, "", 0, 0, "", 0);
  }

  public String getVirtualNodeId() {
    return virtualNodeId;
  }

  public long getCurrentTerm() {
    return currentTerm;
  }

  public String getVotedFor() {
    return votedFor;
  }

  public long getAcceptedEpoch() {
    return acceptedEpoch;
  }

  public long getCommitIndex() {
    return commitIndex;
  }

  public String getLeaseOwner() {
    return leaseOwner;
  }

  public long getLeaseExpireAtMillis() {
    return leaseExpireAtMillis;
  }

  /**
   * 判断指定候选人在给定任期是否可以获得投票。
   *
   * @param candidateId 候选 data 节点标识
   * @param term 请求投票任期
   * @return 可投票或幂等重复投票时返回 true
   */
  public boolean canGrantVote(String candidateId, long term) {
    String candidate = normalizeRequired(candidateId, "candidateId");
    nonNegative(term, "term");
    if (term < currentTerm) {
      return false;
    }
    return term > currentTerm || votedFor.isEmpty() || votedFor.equals(candidate);
  }

  /**
   * 生成授票后的新状态。
   *
   * @param candidateId 候选 data 节点标识
   * @param term 请求投票任期
   * @return 授票后的状态
   * @throws IllegalArgumentException 当请求无法授票时抛出
   */
  public WitnessState grantVote(String candidateId, long term) {
    String candidate = normalizeRequired(candidateId, "candidateId");
    if (!canGrantVote(candidate, term)) {
      throw new IllegalArgumentException(
          "cannot grant vote to " + candidate + " at term " + term);
    }
    return new WitnessState(virtualNodeId, term, candidate, acceptedEpoch,
        commitIndex, leaseOwner, leaseExpireAtMillis);
  }

  /**
   * 接受新的元数据 epoch，禁止 epoch 回退。
   *
   * @param epoch 新 epoch
   * @return 更新后的状态
   * @throws IllegalArgumentException 当 epoch 小于已接受 epoch 时抛出
   */
  public WitnessState acceptEpoch(long epoch) {
    nonNegative(epoch, "epoch");
    if (epoch < acceptedEpoch) {
      throw new IllegalArgumentException(
          "epoch regression from " + acceptedEpoch + " to " + epoch);
    }
    return new WitnessState(virtualNodeId, currentTerm, votedFor, epoch,
        commitIndex, leaseOwner, leaseExpireAtMillis);
  }

  /**
   * 记录 witness 已观察到的提交位置，提交位置只能前进。
   *
   * @param newCommitIndex 新提交位置
   * @return 更新后的状态
   */
  public WitnessState observeCommitIndex(long newCommitIndex) {
    nonNegative(newCommitIndex, "newCommitIndex");
    long nextCommitIndex = Math.max(commitIndex, newCommitIndex);
    return new WitnessState(virtualNodeId, currentTerm, votedFor, acceptedEpoch,
        nextCommitIndex, leaseOwner, leaseExpireAtMillis);
  }

  /**
   * 记录可选 leader lease。
   *
   * @param owner lease 持有者
   * @param expireAtMillis lease 过期时间
   * @return 更新后的状态
   */
  public WitnessState withLease(String owner, long expireAtMillis) {
    return new WitnessState(virtualNodeId, currentTerm, votedFor, acceptedEpoch,
        commitIndex, owner, expireAtMillis);
  }

  private static String normalizeRequired(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return value.trim();
  }

  private static String normalizeOptional(String value) {
    return value == null ? "" : value.trim();
  }

  private static long nonNegative(long value, String fieldName) {
    if (value < 0) {
      throw new IllegalArgumentException(fieldName + " is negative: " + value);
    }
    return value;
  }
}
