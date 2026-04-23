package net.xdob.vexra.ldb.table;

import net.xdob.vexra.ldb.util.Slice;

import java.util.Comparator;

// todo this interface needs more thought
public interface UserComparator
    extends Comparator<Slice> {
  String name();

  Slice findShortestSeparator(Slice start, Slice limit);

  Slice findShortSuccessor(Slice key);
}
