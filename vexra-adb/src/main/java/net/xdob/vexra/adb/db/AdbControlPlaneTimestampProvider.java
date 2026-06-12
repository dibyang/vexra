package net.xdob.vexra.adb.db;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于控制面客户端的 ADB timestamp provider。
 *
 * <p>该实现把 TxnManager 的 startTs/commitTs 分配委托给
 * {@link AdbControlPlaneClient#nextTimestamp()}，并在本地记录最新分配值供
 * statement read timestamp 使用。</p>
 */
public final class AdbControlPlaneTimestampProvider
    implements AdbTimestampProvider {
  private final AdbControlPlaneClient client;
  private final AtomicLong lastTimestamp = new AtomicLong(0);

  /**
   * 创建控制面 timestamp provider。
   *
   * @param client 控制面客户端
   */
  public AdbControlPlaneTimestampProvider(AdbControlPlaneClient client) {
    this.client = Objects.requireNonNull(client, "client == null");
  }

  @Override
  public long nextStartTimestamp() {
    return next();
  }

  @Override
  public long nextCommitTimestamp() {
    return next();
  }

  @Override
  public long lastTimestamp() {
    return lastTimestamp.get();
  }

  private long next() {
    long ts = client.nextTimestamp();
    if (ts <= 0) {
      throw new IllegalStateException("invalid control-plane timestamp: " + ts);
    }
    lastTimestamp.updateAndGet(previous -> Math.max(previous, ts));
    return ts;
  }
}
