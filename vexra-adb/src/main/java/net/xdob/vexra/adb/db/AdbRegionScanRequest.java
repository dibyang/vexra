package net.xdob.vexra.adb.db;

import net.xdob.vexra.cluster.sql.RegionScanTask;

import java.util.Objects;

/**
 * ADB region scan 请求。
 *
 * <p>该对象是远程 region scan executor 的传输边界模型。它携带 region scan task、
 * 事务读视图、count-only 标记和请求超时。真实 RPC 层后续可以直接序列化这些字段，
 * 本阶段则用于进程内 fake 和本地 bridge。</p>
 */
public final class AdbRegionScanRequest {
  private final RegionScanTask task;
  private final long txnId;
  private final long startTs;
  private final boolean countOnly;
  private final long timeoutMillis;

  /**
   * 创建 ADB region scan 请求。
   *
   * @param task region scan task
   * @param txnId 事务 ID
   * @param startTs 事务读时间戳
   * @param countOnly 是否为 count-only scan
   * @param timeoutMillis 请求超时时间，0 表示不限制
   */
  public AdbRegionScanRequest(RegionScanTask task, long txnId, long startTs,
      boolean countOnly, long timeoutMillis) {
    if (txnId < 0) {
      throw new IllegalArgumentException("txnId is negative: " + txnId);
    }
    if (startTs < 0) {
      throw new IllegalArgumentException("startTs is negative: " + startTs);
    }
    if (timeoutMillis < 0) {
      throw new IllegalArgumentException(
          "timeoutMillis is negative: " + timeoutMillis);
    }
    this.task = Objects.requireNonNull(task, "task == null");
    this.txnId = txnId;
    this.startTs = startTs;
    this.countOnly = countOnly;
    this.timeoutMillis = timeoutMillis;
  }

  public RegionScanTask getTask() {
    return task;
  }

  public long getTxnId() {
    return txnId;
  }

  public long getStartTs() {
    return startTs;
  }

  public boolean isCountOnly() {
    return countOnly;
  }

  public long getTimeoutMillis() {
    return timeoutMillis;
  }

  /**
   * 从请求重建本地只读事务视图。
   *
   * @return 用于本地 adapter 的事务对象
   */
  public Transaction2 toReadOnlyTransaction() {
    Transaction2 txn = new Transaction2(txnId, startTs);
    txn.setStartTs(startTs);
    txn.setState(TxnState.PENDING);
    return txn;
  }
}
