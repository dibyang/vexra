package net.xdob.vexra.ldb.impl;


import net.xdob.vexra.ldb.table.UserComparator;
import net.xdob.vexra.ldb.util.Slice;

public class BytewiseComparator implements UserComparator {

  @Override
  public String name() {
    return "leveldb.BytewiseComparator";
  }

  @Override
  public int compare(Slice a, Slice b) {
    int minLength = Math.min(a.length(), b.length());
    for (int i = 0; i < minLength; i++) {
      int av = a.getUnsignedByte(i);
      int bv = b.getUnsignedByte(i);
      if (av != bv) {
        return av - bv;
      }
    }
    return a.length() - b.length();
  }

  @Override
  public Slice findShortestSeparator(Slice start, Slice limit) {
    int minLength = Math.min(start.length(), limit.length());
    int diffIndex = 0;
    while (diffIndex < minLength &&
        start.getUnsignedByte(diffIndex) == limit.getUnsignedByte(diffIndex)) {
      diffIndex++;
    }

    if (diffIndex < minLength) {
      int diffByte = start.getUnsignedByte(diffIndex);
      if (diffByte < 0xff && diffByte + 1 < limit.getUnsignedByte(diffIndex)) {
        byte[] bytes = start.copyBytes(0, diffIndex + 1);
        bytes[diffIndex] = (byte) (diffByte + 1);
        return new Slice(bytes);
      }
    }
    return start;
  }

  @Override
  public Slice findShortSuccessor(Slice key) {
    for (int i = 0; i < key.length(); i++) {
      int b = key.getUnsignedByte(i);
      if (b != 0xff) {
        byte[] bytes = key.copyBytes(0, i + 1);
        bytes[i] = (byte) (b + 1);
        return new Slice(bytes);
      }
    }
    return key;
  }
}
