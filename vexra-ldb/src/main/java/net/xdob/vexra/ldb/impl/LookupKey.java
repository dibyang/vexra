package net.xdob.vexra.ldb.impl;

import net.xdob.vexra.ldb.util.Slice;

public class LookupKey {
  private final InternalKey key;

  public LookupKey(Slice userKey, long sequenceNumber) {
    key = new InternalKey(userKey, sequenceNumber, ValueType.VALUE);
  }

  public InternalKey getInternalKey() {
    return key;
  }

  public Slice getUserKey() {
    return key.getUserKey();
  }

  @Override
  public String toString() {
    return key.toString();
  }
}
