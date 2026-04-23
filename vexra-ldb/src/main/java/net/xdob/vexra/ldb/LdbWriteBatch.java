package net.xdob.vexra.ldb;

import net.xdob.vexra.ldb.util.Slice;

import java.io.Closeable;
import java.util.Set;


public interface LdbWriteBatch
    extends Closeable {
  int OP_TYPE_SIZE = 1;
  int CF_ID_SIZE = 1;
  int MAX_VAR_INT_SIZE = 5;

  LdbWriteBatch touch(LdbColumnFamily cf);

  LdbWriteBatch put(LdbColumnFamily cf, byte[] key, byte[] value);

  LdbWriteBatch delete(LdbColumnFamily cf, byte[] key);

  LdbWriteBatch deleteRange(LdbColumnFamily cf, byte[] beginKey, byte[] endKey);

  LdbWriteBatch put(byte[] key, byte[] value);

  LdbWriteBatch delete(byte[] key);

  LdbWriteBatch deleteRange(byte[] beginKey, byte[] endKey);

  LdbWriteBatch put(LdbColumnFamily cf, Slice key, Slice value);

  LdbWriteBatch delete(LdbColumnFamily cf, Slice key);

  LdbWriteBatch deleteRange(LdbColumnFamily cf, Slice beginKey, Slice endKey);

  LdbWriteBatch put(Slice key, Slice value);

  LdbWriteBatch delete(Slice key);

  LdbWriteBatch deleteRange(Slice beginKey, Slice endKey);

  LdbWriteBatch addLong(LdbColumnFamily cf, byte[] key, long delta);

  LdbWriteBatch addLong(byte[] key, long delta);

  LdbWriteBatch addLong(LdbColumnFamily cf, Slice key, Slice delta);

  Set<LdbColumnFamily> getColumnFamilies();

  boolean isEmpty();

  int size();
}
