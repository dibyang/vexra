package net.xdob.vexra.adb.db;

/**
 * 版本扫描源接口，提供数据版本扫描的功能
 * 支持范围查询、迭代遍历等操作
 */
public interface VersionScanSource extends AutoCloseable {
  /**
   * 获取扫描方向
   *
   * @return 扫描方向枚举值
   */
  ScanDirection direction();
  /**
   * 定位到指定范围的起始位置
   *
   * @param lowerInclusive 范围下界（包含）
   * @param upperExclusive 范围上界（不包含）
   */
  void seekToRangeStart(byte[] lowerInclusive, byte[] upperExclusive);
  /**
   * 检查当前迭代器位置是否有效
   *
   * @return 如果当前位置有效返回true，否则返回false
   */
  boolean isValid();
  /**
   * 获取当前位置的键
   *
   * @return 当前键的字节数组，如果位置无效则返回null
   */
  byte[] key();
  /**
   * 获取当前位置的值
   *
   * @return 当前值的字节数组，如果位置无效则返回null
   */
  byte[] value();
  /**
   * 将迭代器移动到下一个位置
   * 根据扫描方向决定是向前还是向后移动
   */
  void advance();
}
