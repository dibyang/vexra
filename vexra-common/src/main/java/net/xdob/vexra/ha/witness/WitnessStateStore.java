package net.xdob.vexra.ha.witness;

import java.io.IOException;

/**
 * Witness 仲裁状态持久化接口。
 *
 * <p>实现必须保证 {@link #save(WitnessState)} 在返回成功前状态已经持久化，
 * 否则 witness 重启后可能重复投票。</p>
 */
public interface WitnessStateStore {
  /**
   * 加载指定虚节点的 witness 状态。
   *
   * @param virtualNodeId 虚节点标识
   * @return 已持久化状态；不存在时返回空状态
   * @throws IOException 当读取失败时抛出
   */
  WitnessState load(String virtualNodeId) throws IOException;

  /**
   * 保存指定 witness 状态。
   *
   * @param state witness 仲裁状态
   * @throws IOException 当写入失败时抛出
   */
  void save(WitnessState state) throws IOException;
}
