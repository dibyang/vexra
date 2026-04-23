package net.xdob.vexra.ldb.impl;

import net.xdob.vexra.ldb.FilterPolicy;
import net.xdob.vexra.ldb.util.Slice;

import java.util.List;

public final class BloomFilterPolicy implements FilterPolicy {
  private final int bitsPerKey;
  private final int k;

  public BloomFilterPolicy(int bitsPerKey) {
    this.bitsPerKey = bitsPerKey;
    int calcK = (int) Math.round(bitsPerKey * 0.69); // ln(2)
    if (calcK < 1) calcK = 1;
    if (calcK > 30) calcK = 30;
    this.k = calcK;
  }

  @Override
  public String name() {
    return "vexra.BuiltinBloomFilter2";
  }

  @Override
  public byte[] createFilter(List<Slice> keys) {
    int bits = Math.max(64, keys.size() * bitsPerKey);
    int bytes = (bits + 7) / 8;
    bits = bytes * 8;

    byte[] array = new byte[bytes + 1]; // 最后一个字节存 k
    for (Slice key : keys) {
      int h = bloomHash(key);
      int delta = Integer.rotateRight(h, 17);
      for (int j = 0; j < k; j++) {
        int bitpos = (h & 0x7fffffff) % bits;
        array[bitpos / 8] |= (byte) (1 << (bitpos % 8));
        h += delta;
      }
    }
    array[bytes] = (byte) k;
    return array;
  }

  @Override
  public boolean keyMayMatch(Slice key, Slice filter) {
    int length = filter.length();
    if (length < 2) {
      return false;
    }

    byte[] data = filter.getRawArray();
    int base = filter.getRawOffset();

    int bits = (length - 1) * 8;
    int k = data[base + length - 1] & 0xff;
    if (k > 30) {
      return true;
    }

    int h = bloomHash(key);
    int delta = Integer.rotateRight(h, 17);
    for (int j = 0; j < k; j++) {
      int bitpos = (h & 0x7fffffff) % bits;
      int idx = base + (bitpos / 8);
      if ((data[idx] & (1 << (bitpos % 8))) == 0) {
        return false;
      }
      h += delta;
    }
    return true;
  }


  private static int bloomHash(Slice key) {
    return key.hashCode();
  }
}
