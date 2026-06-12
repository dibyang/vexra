package net.xdob.vexra.adb.db;

import java.util.Arrays;

/**
 * ADB MVCC lock 记录。
 *
 * <p>该对象用于 ADB-Prod-02 的 lock resolve 运行时入口。相比 common
 * `TxnLock`，这里额外保存 txnId，因为当前 ADB durable intent 通过
 * `TxnRefKey(txnId, ...)` 回滚，resolver 必须知道原事务 ID 才能安全清理。</p>
 */
public final class AdbTxnLock {
  private final long txnId;
  private final byte[] key;
  private final byte[] primaryKey;
  private final long startTs;
  private final String regionId;
  private final long ttlMillis;

  /**
   * 创建 ADB lock 记录。
   *
   * @param txnId 事务 ID，用于 rollback durable intent
   * @param key 被锁 key
   * @param primaryKey primary lock key
   * @param startTs 事务开始时间戳
   * @param regionId lock 所属 region
   * @param ttlMillis lock TTL
   */
  public AdbTxnLock(long txnId, byte[] key, byte[] primaryKey, long startTs,
      String regionId, long ttlMillis) {
    if (txnId < 0) {
      throw new IllegalArgumentException("txnId is negative: " + txnId);
    }
    if (startTs <= 0) {
      throw new IllegalArgumentException("startTs must be positive");
    }
    if (regionId == null || regionId.trim().isEmpty()) {
      throw new IllegalArgumentException("regionId is empty");
    }
    if (ttlMillis < 0) {
      throw new IllegalArgumentException("ttlMillis is negative");
    }
    this.txnId = txnId;
    this.key = copyRequired(key, "key");
    this.primaryKey = copyRequired(primaryKey, "primaryKey");
    this.startTs = startTs;
    this.regionId = regionId.trim();
    this.ttlMillis = ttlMillis;
  }

  public long getTxnId() {
    return txnId;
  }

  public byte[] getKey() {
    return copyRequired(key, "key");
  }

  public byte[] getPrimaryKey() {
    return copyRequired(primaryKey, "primaryKey");
  }

  public long getStartTs() {
    return startTs;
  }

  public String getRegionId() {
    return regionId;
  }

  public long getTtlMillis() {
    return ttlMillis;
  }

  /**
   * 判断 lock 在指定时间戳下是否已过期。
   *
   * @param nowTs 当前时间戳
   * @return 已过期返回 true，否则返回 false
   */
  public boolean isExpired(long nowTs) {
    if (nowTs < startTs) {
      return false;
    }
    return nowTs - startTs > ttlMillis;
  }

  private static byte[] copyRequired(byte[] value, String fieldName) {
    if (value == null || value.length == 0) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return Arrays.copyOf(value, value.length);
  }
}
