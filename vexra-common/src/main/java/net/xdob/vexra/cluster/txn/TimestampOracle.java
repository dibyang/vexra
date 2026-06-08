package net.xdob.vexra.cluster.txn;

/**
 * 全局时间戳服务接口。
 *
 * <p>分布式事务使用单调递增时间戳作为 startTs 和 commitTs。实现必须保证同一服务实例
 * 返回值严格递增。</p>
 */
public interface TimestampOracle {
  /**
   * 获取下一个全局时间戳。
   *
   * @return 严格递增时间戳
   */
  long nextTimestamp();
}
