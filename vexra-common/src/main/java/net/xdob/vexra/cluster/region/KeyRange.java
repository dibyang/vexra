package net.xdob.vexra.cluster.region;

import java.util.Arrays;

/**
 * 字节序 key range。
 *
 * <p>范围采用左闭右开语义：[startKey, endKey)。空数组表示无边界，
 * 即空 startKey 为负无穷，空 endKey 为正无穷。</p>
 */
public final class KeyRange {
  private static final byte[] EMPTY = new byte[0];

  private final byte[] startKey;
  private final byte[] endKey;

  /**
   * 创建 key range。
   *
   * @param startKey 起始 key，空数组表示无下界
   * @param endKey 结束 key，空数组表示无上界
   */
  public KeyRange(byte[] startKey, byte[] endKey) {
    this.startKey = copy(startKey);
    this.endKey = copy(endKey);
    if (hasStart() && hasEnd() && compare(this.startKey, this.endKey) >= 0) {
      throw new IllegalArgumentException("startKey must be smaller than endKey");
    }
  }

  public byte[] getStartKey() {
    return copy(startKey);
  }

  public byte[] getEndKey() {
    return copy(endKey);
  }

  /**
   * 判断 key 是否落在当前范围内。
   *
   * @param key 待判断 key
   * @return 在范围内返回 true
   */
  public boolean contains(byte[] key) {
    byte[] normalized = copy(key);
    return (!hasStart() || compare(normalized, startKey) >= 0)
        && (!hasEnd() || compare(normalized, endKey) < 0);
  }

  /**
   * 判断两个范围是否有交集。
   *
   * @param other 另一个范围
   * @return 有交集返回 true
   */
  public boolean overlaps(KeyRange other) {
    if (other == null) {
      return false;
    }
    return (isUnboundedEnd(endKey) || isUnboundedStart(other.startKey)
        || compare(endKey, other.startKey) > 0)
        && (isUnboundedEnd(other.endKey) || isUnboundedStart(startKey)
        || compare(other.endKey, startKey) > 0);
  }

  /**
   * 按无符号字节序比较 key。
   *
   * @param left 左 key
   * @param right 右 key
   * @return 小于、等于或大于时分别返回负数、0、正数
   */
  public static int compare(byte[] left, byte[] right) {
    byte[] a = copy(left);
    byte[] b = copy(right);
    int min = Math.min(a.length, b.length);
    for (int i = 0; i < min; i++) {
      int diff = (a[i] & 0xff) - (b[i] & 0xff);
      if (diff != 0) {
        return diff;
      }
    }
    return a.length - b.length;
  }

  private boolean hasStart() {
    return !isUnboundedStart(startKey);
  }

  private boolean hasEnd() {
    return !isUnboundedEnd(endKey);
  }

  private static boolean isUnboundedStart(byte[] key) {
    return key.length == 0;
  }

  private static boolean isUnboundedEnd(byte[] key) {
    return key.length == 0;
  }

  private static byte[] copy(byte[] key) {
    return key == null || key.length == 0 ? EMPTY : Arrays.copyOf(key, key.length);
  }
}
