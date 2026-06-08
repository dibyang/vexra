package net.xdob.vexra.cluster.txn;

import java.util.Arrays;

/**
 * 分布式事务锁记录。
 *
 * <p>该对象描述 MVCC lock 列中的最小语义：被锁 key、primary key、startTs、
 * 所属 region 和 TTL。lock resolve 可通过 {@link #isExpired(long)} 判断是否需要清理。</p>
 */
public final class TxnLock {
  private final byte[] key;
  private final byte[] primaryKey;
  private final long startTs;
  private final String regionId;
  private final long ttlMillis;

  /**
   * 创建事务锁记录。
   *
   * @param key 被锁 key
   * @param primaryKey primary lock key
   * @param startTs 事务开始时间戳
   * @param regionId 所属 region
   * @param ttlMillis 锁 TTL
   */
  public TxnLock(byte[] key, byte[] primaryKey, long startTs, String regionId,
      long ttlMillis) {
    this.key = copyRequired(key, "key");
    this.primaryKey = copyRequired(primaryKey, "primaryKey");
    if (startTs <= 0) {
      throw new IllegalArgumentException("startTs must be positive");
    }
    if (regionId == null || regionId.trim().isEmpty()) {
      throw new IllegalArgumentException("regionId is empty");
    }
    if (ttlMillis < 0) {
      throw new IllegalArgumentException("ttlMillis is negative");
    }
    this.startTs = startTs;
    this.regionId = regionId.trim();
    this.ttlMillis = ttlMillis;
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
   * 判断锁在指定时间戳下是否已过期。
   *
   * @param nowTs 当前时间戳
   * @return 已过期返回 true
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
