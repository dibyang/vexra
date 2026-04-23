package net.xdob.vexra.adb.db;

/**
 * 可见索引解析器接口，用于获取事务可见的索引数据
 */
public interface VisibleIndexResolver {
  /**
   * 获取事务可见的索引数据
   *
   * @param txn 当前事务上下文
   * @param logicalPrefix 逻辑前缀字节数组
   * @return 事务可见的索引行值，如果不存在则返回null
   */
  RowValue getVisibleIndex(Transaction2 txn, byte[] logicalPrefix);
}
