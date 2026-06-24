package net.xdob.vexra.adb.db;

import net.xdob.vexra.ldb.util.Slice;

/**
 * ADB 版本读会话。
 *
 * <p>该接口用于表达“同一 worker 内复用读视图/底层 cursor”的物理 KV 读路径。它只承诺
 * 当前 key 范围内的物理记录语义，不等同于 ADB SQL 层的 MVCC 可见逻辑行语义。</p>
 */
public interface VersionReadSession extends AutoCloseable {
  /**
   * 统计闭区间内的物理 KV 记录数。
   *
   * @param beginInclusive 起始 key，包含
   * @param endInclusive 结束 key，包含
   * @return 闭区间内的物理 KV 记录数
   */
  long countClosed(byte[] beginInclusive, byte[] endInclusive);

  /**
   * 使用低分配 view 扫描闭区间内的物理 KV 记录。
   *
   * @param beginInclusive 起始 key，包含
   * @param endInclusive 结束 key，包含
   * @param visitor 当前 key/value view 回调；view 只在当前回调内有效
   */
  void scanClosed(byte[] beginInclusive, byte[] endInclusive,
      EntryVisitor visitor);

  @Override
  void close();

  /**
   * 低分配 KV 访问回调。
   */
  interface EntryVisitor {
    /**
     * 访问一条物理 KV 记录。
     *
     * @param keyView 当前 key view，只在回调内有效
     * @param valueView 当前 value view，只在回调内有效
     */
    void visit(Slice keyView, Slice valueView);
  }
}
