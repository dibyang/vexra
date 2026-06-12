package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.DataKey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * ADB region commit 请求。
 *
 * <p>该对象描述一次已经完成本地事务校验、write gate fencing 和 timestamp 分配后的
 * region 事务阶段请求。真实 region Raft/RPC client 可以用它执行 prewrite/commit/
 * rollback；本地 bridge client 则用它复用现有
 * {@link net.xdob.vexra.adb.DbStore#commitAsync(long, long, List)} 语义。</p>
 */
public final class AdbRegionCommitRequest {
  private final String regionId;
  private final long regionEpoch;
  private final String leaderId;
  private final long txnId;
  private final long startTs;
  private final long commitTs;
  private final String primaryRegionId;
  private final DataKey primaryKey;
  private final long lockTtlMillis;
  private final boolean primaryRegion;
  private final List<DataKey> writeKeys;
  private final List<Meta> metas;

  /**
   * 创建 region commit 请求。
   *
   * @param regionId region 标识
   * @param regionEpoch region epoch
   * @param leaderId leader 副本标识
   * @param txnId ADB 事务 ID
   * @param commitTs ADB commit timestamp
   * @param writeKeys 当前事务写入 key 快照
   * @param metas 提交时附带的元数据更新
   */
  public AdbRegionCommitRequest(String regionId, long regionEpoch,
      String leaderId, long txnId, long commitTs, Collection<DataKey> writeKeys,
      List<Meta> metas) {
    this(regionId, regionEpoch, leaderId, txnId, 0, commitTs, null, null, 0,
        false, writeKeys, metas);
  }

  /**
   * 创建带 2PC 元信息的 region 事务请求。
   *
   * @param regionId region 标识
   * @param regionEpoch region epoch
   * @param leaderId leader 副本标识
   * @param txnId ADB 事务 ID
   * @param startTs ADB start timestamp
   * @param commitTs ADB commit timestamp
   * @param primaryRegionId primary participant region 标识
   * @param primaryKey primary lock key
   * @param lockTtlMillis primary/secondary lock TTL
   * @param primaryRegion 当前请求是否属于 primary participant
   * @param writeKeys 当前 region 的写入 key 快照
   * @param metas 提交时附带的元数据更新
   */
  public AdbRegionCommitRequest(String regionId, long regionEpoch,
      String leaderId, long txnId, long startTs, long commitTs,
      String primaryRegionId, DataKey primaryKey, long lockTtlMillis,
      boolean primaryRegion, Collection<DataKey> writeKeys, List<Meta> metas) {
    this.regionId = normalize(regionId, "regionId");
    if (regionEpoch < 0) {
      throw new IllegalArgumentException("regionEpoch is negative: "
          + regionEpoch);
    }
    this.regionEpoch = regionEpoch;
    this.leaderId = normalize(leaderId, "leaderId");
    if (txnId < 0) {
      throw new IllegalArgumentException("txnId is negative: " + txnId);
    }
    if (startTs < 0) {
      throw new IllegalArgumentException("startTs is negative: " + startTs);
    }
    if (commitTs < 0) {
      throw new IllegalArgumentException("commitTs is negative: " + commitTs);
    }
    this.txnId = txnId;
    this.startTs = startTs;
    this.commitTs = commitTs;
    this.primaryRegionId = primaryRegionId == null ? null
        : normalize(primaryRegionId, "primaryRegionId");
    this.primaryKey = primaryKey;
    if (lockTtlMillis < 0) {
      throw new IllegalArgumentException("lockTtlMillis is negative: "
          + lockTtlMillis);
    }
    this.lockTtlMillis = lockTtlMillis;
    this.primaryRegion = primaryRegion;
    this.writeKeys = immutableKeys(writeKeys);
    this.metas = immutableMetas(metas);
  }

  public String getRegionId() {
    return regionId;
  }

  public long getRegionEpoch() {
    return regionEpoch;
  }

  public String getLeaderId() {
    return leaderId;
  }

  public long getTxnId() {
    return txnId;
  }

  public long getStartTs() {
    return startTs;
  }

  public long getCommitTs() {
    return commitTs;
  }

  public String getPrimaryRegionId() {
    return primaryRegionId;
  }

  public DataKey getPrimaryKey() {
    return primaryKey;
  }

  public long getLockTtlMillis() {
    return lockTtlMillis;
  }

  public boolean isPrimaryRegion() {
    return primaryRegion;
  }

  public List<DataKey> getWriteKeys() {
    return writeKeys;
  }

  public List<Meta> getMetas() {
    return metas;
  }

  private static List<DataKey> immutableKeys(Collection<DataKey> writeKeys) {
    Objects.requireNonNull(writeKeys, "writeKeys == null");
    if (writeKeys.isEmpty()) {
      throw new IllegalArgumentException("writeKeys is empty");
    }
    List<DataKey> copy = new ArrayList<>();
    for (DataKey key : writeKeys) {
      copy.add(Objects.requireNonNull(key, "writeKey == null"));
    }
    return Collections.unmodifiableList(copy);
  }

  private static List<Meta> immutableMetas(List<Meta> metas) {
    if (metas == null || metas.isEmpty()) {
      return Collections.emptyList();
    }
    return Collections.unmodifiableList(new ArrayList<>(metas));
  }

  private static String normalize(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return value.trim();
  }
}
