package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.DataKey;

/**
 * 可见行解析器接口，用于获取事务可见的数据行
 */
public interface VisibleRowResolver {
  /**
   * 获取事务可见的最新版本数据行
   *
   * @param txn 当前事务上下文
   * @param dataKey 数据键
   * @return 事务可见的行值，如果不存在则返回null
   */
  RowValue getVisible(Transaction2 txn, DataKey dataKey);
}
