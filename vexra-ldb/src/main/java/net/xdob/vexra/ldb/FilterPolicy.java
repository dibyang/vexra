package net.xdob.vexra.ldb;

import net.xdob.vexra.ldb.util.Slice;

import java.util.List;

public interface FilterPolicy {
  String name();

  byte[] createFilter(List<Slice> keys);

  boolean keyMayMatch(Slice key, Slice filter);
}
