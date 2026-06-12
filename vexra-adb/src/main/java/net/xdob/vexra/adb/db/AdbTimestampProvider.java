package net.xdob.vexra.adb.db;

/**
 * ADB 外部时间戳提供器。
 *
 * <p>该接口用于把控制面 TSO 接入 {@link TxnManager}。未配置时 TxnManager
 * 继续使用现有本地计数器；配置后事务 startTs 和 commitTs 都来自该 provider。</p>
 */
public interface AdbTimestampProvider {
  /**
   * 分配事务 start timestamp。
   *
   * @return 全局单调时间戳
   */
  long nextStartTimestamp();

  /**
   * 分配事务 commit timestamp。
   *
   * @return 全局单调时间戳
   */
  long nextCommitTimestamp();

  /**
   * 返回当前 provider 已分配的最新时间戳。
   *
   * @return 最新时间戳，没有分配过时由实现决定返回值
   */
  long lastTimestamp();
}
