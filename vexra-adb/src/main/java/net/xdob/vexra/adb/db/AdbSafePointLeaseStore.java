package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.DbStore;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/**
 * ADB safe point lease 本地持久化 store。
 *
 * <p>该类把全局 safe point 和本地 GC worker lease 写入 META CF。它提供单
 * store 内的抢占、续租、释放和 safe point 单调持久化边界；真正跨进程的线性一致
 * CAS、PD/etcd lease 和复制由后续控制面实现替换。</p>
 */
public final class AdbSafePointLeaseStore {
  private static final byte[] KEY =
      "adb.safe-point.global.v1".getBytes(StandardCharsets.UTF_8);
  private static final int MAGIC = 0x41535031;
  private static final int VERSION = 1;

  private final DbStore store;

  /**
   * 创建 safe point lease store。
   *
   * @param store ADB store
   */
  public AdbSafePointLeaseStore(DbStore store) {
    this.store = Objects.requireNonNull(store, "store == null");
  }

  /**
   * 读取当前 safe point lease 记录。
   *
   * @return 当前记录，未初始化时返回 safePoint=0 的空记录
   * @throws SQLException 底层读取失败或记录格式损坏时抛出
   */
  public synchronized AdbSafePointLeaseRecord read() throws SQLException {
    byte[] value = store.get(CF.META.getCfId(), KEY);
    if (value == null) {
      return emptyRecord();
    }
    return decode(value);
  }

  /**
   * 尝试获取或续租 safe point lease。
   *
   * @param ownerId 申请者标识
   * @param nowMillis 当前时间戳
   * @param leaseMillis lease 时长，必须大于 0
   * @return 获取成功返回新记录；未过期租约由其他 owner 持有时返回空
   * @throws SQLException 底层写入失败时抛出
   */
  public synchronized Optional<AdbSafePointLeaseRecord> tryAcquire(
      String ownerId, long nowMillis, long leaseMillis) throws SQLException {
    String owner = normalizeOwner(ownerId);
    nonNegative(nowMillis, "nowMillis");
    if (leaseMillis <= 0) {
      throw new IllegalArgumentException("leaseMillis must be positive");
    }
    AdbSafePointLeaseRecord current = read();
    if (current.isLeaseActive(nowMillis)
        && !current.getOwnerId().equals(owner)) {
      return Optional.empty();
    }
    AdbSafePointLeaseRecord next = new AdbSafePointLeaseRecord(
        current.getSafePoint(), owner, nowMillis + leaseMillis);
    persist(next);
    return Optional.of(next);
  }

  /**
   * 由 lease 持有者持久化推进 safe point。
   *
   * @param ownerId 当前 lease owner
   * @param safePoint 新 safe point，不能小于当前持久化值
   * @param nowMillis 当前时间戳
   * @return 更新后的记录
   * @throws SQLException lease 不存在、已过期、owner 不匹配或写入失败时抛出
   */
  public synchronized AdbSafePointLeaseRecord advanceSafePoint(
      String ownerId, long safePoint, long nowMillis) throws SQLException {
    String owner = normalizeOwner(ownerId);
    nonNegative(safePoint, "safePoint");
    nonNegative(nowMillis, "nowMillis");
    AdbSafePointLeaseRecord current = read();
    if (!current.isHeldBy(owner, nowMillis)) {
      throw new SQLException("Safe point lease is not held by owner="
          + owner);
    }
    if (safePoint < current.getSafePoint()) {
      throw new IllegalArgumentException("safe point cannot move backward, "
          + "current=" + current.getSafePoint() + ", next=" + safePoint);
    }
    AdbSafePointLeaseRecord next = new AdbSafePointLeaseRecord(safePoint,
        current.getOwnerId(), current.getLeaseUntilMillis());
    persist(next);
    return next;
  }

  /**
   * 释放当前 owner 持有的 lease。
   *
   * @param ownerId 当前 lease owner
   * @param nowMillis 当前时间戳
   * @return 释放成功返回 true；租约不存在、已过期或 owner 不匹配时返回 false
   * @throws SQLException 底层写入失败时抛出
   */
  public synchronized boolean release(String ownerId, long nowMillis)
      throws SQLException {
    String owner = normalizeOwner(ownerId);
    nonNegative(nowMillis, "nowMillis");
    AdbSafePointLeaseRecord current = read();
    if (!current.isHeldBy(owner, nowMillis)) {
      return false;
    }
    persist(new AdbSafePointLeaseRecord(current.getSafePoint(), "", 0));
    return true;
  }

  private void persist(AdbSafePointLeaseRecord record) throws SQLException {
    store.put(CF.META.getCfId(), KEY, encode(record));
  }

  private static AdbSafePointLeaseRecord emptyRecord() {
    return new AdbSafePointLeaseRecord(0, "", 0);
  }

  private static byte[] encode(AdbSafePointLeaseRecord record) {
    byte[] ownerBytes = record.getOwnerId().getBytes(StandardCharsets.UTF_8);
    ByteBuffer buffer = ByteBuffer.allocate(4 + 4 + 8 + 8 + 4
        + ownerBytes.length);
    buffer.putInt(MAGIC);
    buffer.putInt(VERSION);
    buffer.putLong(record.getSafePoint());
    buffer.putLong(record.getLeaseUntilMillis());
    buffer.putInt(ownerBytes.length);
    buffer.put(ownerBytes);
    return buffer.array();
  }

  private static AdbSafePointLeaseRecord decode(byte[] value)
      throws SQLException {
    try {
      ByteBuffer buffer = ByteBuffer.wrap(value);
      int magic = buffer.getInt();
      int version = buffer.getInt();
      if (magic != MAGIC || version != VERSION) {
        throw new SQLException("Invalid safe point lease record header");
      }
      long safePoint = buffer.getLong();
      long leaseUntilMillis = buffer.getLong();
      int ownerLength = buffer.getInt();
      if (ownerLength < 0 || ownerLength > buffer.remaining()) {
        throw new SQLException("Invalid safe point lease owner length");
      }
      byte[] ownerBytes = new byte[ownerLength];
      buffer.get(ownerBytes);
      return new AdbSafePointLeaseRecord(safePoint,
          new String(ownerBytes, StandardCharsets.UTF_8), leaseUntilMillis);
    } catch (RuntimeException e) {
      throw new SQLException("Failed to decode safe point lease record", e);
    }
  }

  private static String normalizeOwner(String ownerId) {
    if (ownerId == null || ownerId.trim().isEmpty()) {
      throw new IllegalArgumentException("ownerId is empty");
    }
    return ownerId.trim();
  }

  private static long nonNegative(long value, String fieldName) {
    if (value < 0) {
      throw new IllegalArgumentException(fieldName + " is negative: "
          + value);
    }
    return value;
  }
}
