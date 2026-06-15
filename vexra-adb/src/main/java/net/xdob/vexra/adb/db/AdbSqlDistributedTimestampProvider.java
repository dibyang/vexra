package net.xdob.vexra.adb.db;

import java.util.concurrent.atomic.AtomicLong;

/**
 * SQL 分布式原型使用的单调时间戳提供器。
 *
 * <p>该实现只用于 SQL server 与 region node 暂未共享真实 TSO 的过渡阶段。配置固定
 * readTs 时，它会从 readTs 之前预留一个小窗口分配 startTs/commitTs，保证本轮 SQL
 * 写入能被后续固定 readTs 的远端 scan 看见；后续应由 `ADB-Run-09` 的真实 catalog/TSO
 * 接管。</p>
 */
public final class AdbSqlDistributedTimestampProvider
    implements AdbTimestampProvider {
  private static final long DEFAULT_WINDOW_BEFORE_READ_TS = 1000L;

  private final AtomicLong current;

  /**
   * 创建 SQL 分布式时间戳提供器。
   *
   * @param fixedReadTimestamp 可选固定读时间戳；null 表示从 0 开始单调分配
   */
  public AdbSqlDistributedTimestampProvider(Long fixedReadTimestamp) {
    long initial = 0L;
    if (fixedReadTimestamp != null) {
      initial = Math.max(0L,
          fixedReadTimestamp - DEFAULT_WINDOW_BEFORE_READ_TS);
    }
    this.current = new AtomicLong(initial);
  }

  @Override
  public long nextStartTimestamp() {
    return current.incrementAndGet();
  }

  @Override
  public long nextCommitTimestamp() {
    return current.incrementAndGet();
  }

  @Override
  public long lastTimestamp() {
    return current.get();
  }
}
