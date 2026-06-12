package net.xdob.vexra.adb.key;

import net.xdob.vexra.adb.db.DynamicByteBuffer;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * ADB TXN CF 中的 MVCC lock 记录 key。
 *
 * <p>key 形态为 txnId + {@link TxnKeyType#LOCK} + cfId + logical key。它不参与
 * DEFAULT CF 的版本排序，只作为 lock resolve / GC worker 后续扫描同一事务 lock
 * 的入口。commit/rollback 必须按 txnId 清理该 key，避免事务结束后留下陈旧 lock。</p>
 */
public final class TxnLockKey extends Key {
  public static final int HEAD_SIZE = 10;
  private final long txnId;
  private final byte cfId;
  private final byte[] key;

  private TxnLockKey(byte[] data) {
    super(data);
    if (this.data.length <= HEAD_SIZE) {
      throw new IllegalArgumentException("Invalid TxnLockKey bytes, length="
          + this.data.length);
    }
    ByteBuffer wrap = ByteBuffer.wrap(this.data);
    txnId = flipSign(wrap.getLong());
    TxnKeyType type = TxnKeyType.getByCode(wrap.get());
    if (type != TxnKeyType.LOCK) {
      throw new IllegalArgumentException("Invalid TxnLockKey type: " + type);
    }
    cfId = wrap.get();
    key = new byte[this.data.length - HEAD_SIZE];
    System.arraycopy(this.data, HEAD_SIZE, key, 0, key.length);
  }

  public long getTxnId() {
    return txnId;
  }

  /**
   * 返回 logical key 所属 CF。
   *
   * @return CF ID
   */
  public byte getCfId() {
    return cfId;
  }

  /**
   * 返回被锁 logical key。
   *
   * @return logical key 副本
   */
  public byte[] getKey() {
    return Arrays.copyOf(key, key.length);
  }

  /**
   * 从 TXN CF key bytes 解析 lock key。
   *
   * @param data TXN CF key bytes
   * @return 解析后的 lock key
   */
  public static TxnLockKey fromBytes(byte[] data) {
    return new TxnLockKey(data);
  }

  /**
   * 创建 TXN CF lock key。
   *
   * @param txnId 事务 ID
   * @param cfId logical key 所属 CF
   * @param key 被锁 logical key
   * @return TXN CF lock key
   */
  public static TxnLockKey of(long txnId, byte cfId, byte[] key) {
    if (key == null || key.length == 0) {
      throw new IllegalArgumentException("key is empty");
    }
    DynamicByteBuffer buffer = DynamicByteBuffer.c();
    buffer.putLong(flipSign(txnId));
    buffer.put(TxnKeyType.LOCK.getCode());
    buffer.put(cfId);
    buffer.put(key);
    return new TxnLockKey(buffer.toArray());
  }
}
