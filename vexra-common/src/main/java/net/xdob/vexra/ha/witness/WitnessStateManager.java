package net.xdob.vexra.ha.witness;

import java.io.IOException;
import java.util.Objects;

/**
 * Witness 投票状态管理器。
 *
 * <p>该类把加载、校验、更新和保存串成同步操作，保证授票状态在返回前已经持久化。
 * 后续 witness RPC 可以把一次投票请求映射到 {@link #grantVote(String, String, long)}。</p>
 */
public final class WitnessStateManager {
  private final WitnessStateStore store;

  /**
   * 创建 witness 状态管理器。
   *
   * @param store witness 状态持久化实现
   */
  public WitnessStateManager(WitnessStateStore store) {
    this.store = Objects.requireNonNull(store, "store == null");
  }

  /**
   * 尝试给候选节点授票，并在返回前持久化投票状态。
   *
   * @param virtualNodeId 虚节点标识
   * @param candidateId 候选 data 节点标识
   * @param term 请求投票任期
   * @return 授票后的状态
   * @throws IOException 当持久化失败时抛出
   * @throws IllegalArgumentException 当请求无法授票时抛出
   */
  public synchronized WitnessState grantVote(String virtualNodeId,
      String candidateId, long term) throws IOException {
    WitnessState next = store.load(virtualNodeId).grantVote(candidateId, term);
    store.save(next);
    return next;
  }

  /**
   * 接受并持久化新的元数据 epoch。
   *
   * @param virtualNodeId 虚节点标识
   * @param epoch 新 epoch
   * @return 更新后的状态
   * @throws IOException 当持久化失败时抛出
   */
  public synchronized WitnessState acceptEpoch(String virtualNodeId, long epoch)
      throws IOException {
    WitnessState next = store.load(virtualNodeId).acceptEpoch(epoch);
    store.save(next);
    return next;
  }

  /**
   * 记录并持久化 witness 观察到的提交位置。
   *
   * @param virtualNodeId 虚节点标识
   * @param commitIndex 新提交位置
   * @return 更新后的状态
   * @throws IOException 当持久化失败时抛出
   */
  public synchronized WitnessState observeCommitIndex(String virtualNodeId,
      long commitIndex) throws IOException {
    WitnessState next = store.load(virtualNodeId).observeCommitIndex(commitIndex);
    store.save(next);
    return next;
  }
}
