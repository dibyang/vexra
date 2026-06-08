package net.xdob.vexra.cluster.txn;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存 TSO 实现。
 *
 * <p>该实现适用于单元测试和单进程原型。生产级 TSO 仍应由控制面或 Raft 状态机提供，
 * 以保证跨节点单调性和持久化。</p>
 */
public final class InMemoryTimestampOracle implements TimestampOracle {
  private final AtomicLong next;

  /**
   * 创建内存 TSO。
   *
   * @param initialValue 初始值，第一次返回值为 initialValue + 1
   */
  public InMemoryTimestampOracle(long initialValue) {
    if (initialValue < 0) {
      throw new IllegalArgumentException("initialValue is negative: " + initialValue);
    }
    this.next = new AtomicLong(initialValue);
  }

  @Override
  public long nextTimestamp() {
    return next.incrementAndGet();
  }
}
