package net.xdob.vexra.ldb.impl;

import net.xdob.vexra.ldb.LdbColumnFamily;
import net.xdob.vexra.ldb.LdbWriteBatch;
import net.xdob.vexra.ldb.WriteOp;
import net.xdob.vexra.ldb.util.Slice;
import net.xdob.vexra.ldb.util.Slices;
import net.xdob.vexra.ldb.util.Utils;

import java.util.*;

import static java.util.Objects.requireNonNull;

public class LdbWriteBatchImpl
    implements LdbWriteBatch {
  private final List<WriteOp> batch = new ArrayList<>();
  private final LinkedHashSet<LdbColumnFamily> touchedCfs = new LinkedHashSet<>();
  private int approximateSize;

  public int getApproximateSize() {
    return approximateSize;
  }

  public int size() {
    return batch.size();
  }

  public LdbWriteBatch touch(LdbColumnFamily cf) {
    requireNonNull(cf, "cf is null");
    touchedCfs.add(cf);
    return this;
  }


  @Override
  public LdbWriteBatch put(LdbColumnFamily cf, byte[] key, byte[] value) {
    return put(cf, Slices.wrappedBuffer(key), Slices.wrappedBuffer(value));
  }


  @Override
  public LdbWriteBatch delete(LdbColumnFamily cf, byte[] key) {
    return delete(cf, Slices.wrappedBuffer(key));
  }

  @Override
  public LdbWriteBatch deleteRange(LdbColumnFamily cf, byte[] beginKey, byte[] endKey) {
    return deleteRange(cf, Slices.wrappedBuffer(beginKey), Slices.wrappedBuffer(endKey));
  }

  @Override
  public LdbWriteBatch put(byte[] key, byte[] value) {
    return put(LdbColumnFamily.DEFAULT, key, value);
  }


  @Override
  public LdbWriteBatch delete(byte[] key) {
    return delete(LdbColumnFamily.DEFAULT,  key);
  }

  @Override
  public LdbWriteBatch deleteRange(byte[] beginKey, byte[] endKey) {
    return deleteRange(LdbColumnFamily.DEFAULT, beginKey, endKey);
  }

  @Override
  public LdbWriteBatch put(LdbColumnFamily cf, Slice key, Slice value) {
    requireNonNull(cf, "cf is null");
    requireNonNull(key, "key is null");
    requireNonNull(value, "value is null");
    batch.add(WriteOp.put(cf, key, value));
    touchedCfs.add(cf);
    approximateSize += CF_ID_SIZE + OP_TYPE_SIZE + MAX_VAR_INT_SIZE + key.length() + MAX_VAR_INT_SIZE + value.length();
    return this;
  }

  @Override
  public LdbWriteBatch delete(LdbColumnFamily cf, Slice key) {
    requireNonNull(cf, "cf is null");
    requireNonNull(key, "key is null");
    batch.add(WriteOp.delete(cf, key));
    touchedCfs.add(cf);
    approximateSize += CF_ID_SIZE + OP_TYPE_SIZE + MAX_VAR_INT_SIZE + key.length();
    return this;
  }

  @Override
  public LdbWriteBatch deleteRange(LdbColumnFamily cf, Slice beginKey, Slice endKey) {
    requireNonNull(cf, "cf is null");
    requireNonNull(beginKey, "beginKey is null");
    requireNonNull(endKey, "endKey is null");
    batch.add(WriteOp.deleteRange(cf, beginKey, endKey));
    touchedCfs.add(cf);
    approximateSize += CF_ID_SIZE + OP_TYPE_SIZE + MAX_VAR_INT_SIZE + beginKey.length() +MAX_VAR_INT_SIZE + endKey.length();
    return this;
  }

  @Override
  public LdbWriteBatch put(Slice key, Slice value) {
    return put(LdbColumnFamily.DEFAULT, key, value);
  }

  @Override
  public LdbWriteBatch delete(Slice key) {
    return delete(LdbColumnFamily.DEFAULT, key);
  }

  @Override
  public LdbWriteBatch deleteRange(Slice beginKey, Slice endKey) {
    return deleteRange(LdbColumnFamily.DEFAULT, beginKey, endKey);
  }

  @Override
  public LdbWriteBatch addLong(LdbColumnFamily cf, byte[] key, long delta) {
    return addLong(cf, Slices.wrappedBuffer(key), Slices.wrappedBuffer(Utils.encodeLong(delta)));
  }

  @Override
  public LdbWriteBatch addLong(byte[] key, long delta) {
    return addLong(LdbColumnFamily.DEFAULT, key, delta);
  }

  @Override
  public LdbWriteBatch addLong(LdbColumnFamily cf, Slice key, Slice delta) {
    requireNonNull(cf, "cf is null");
    requireNonNull(key, "key is null");
    batch.add(WriteOp.addLong(cf, key, delta));
    touchedCfs.add(cf);
    approximateSize += CF_ID_SIZE + OP_TYPE_SIZE + MAX_VAR_INT_SIZE + key.length() + MAX_VAR_INT_SIZE + delta.length();
    return this;
  }

  @Override
  public Set<LdbColumnFamily> getColumnFamilies() {
    return touchedCfs;
  }

  @Override
  public boolean isEmpty() {
    return batch.isEmpty();
  }


  @Override
  public void close() {
  }

  public void forEach(Handler handler) {
    for (WriteOp entry : batch) {
      switch (entry.getType()) {
        case PUT:
          handler.put(entry.getCf(), entry.getKey(), entry.getValue());
          break;
        case DELETE:
          handler.delete(entry.getCf(), entry.getKey());
          break;
        case DELETE_RANGE:
          handler.deleteRange(entry.getCf(), entry.getKey(), entry.getEndKey());
          break;
        case ADD_LONG:
          handler.addLong(entry.getCf(), entry.getKey(), entry.getValue());
          break;
        default:
          throw new IllegalArgumentException("Unknown entry type: " + entry.getType());
      }
    }
  }

  public interface Handler {
    void put(LdbColumnFamily cf, Slice key, Slice value);

    void delete(LdbColumnFamily cf, Slice key);

    default void deleteRange(LdbColumnFamily cf, Slice beginKey, Slice endKey) {
      throw new UnsupportedOperationException("deleteRange is not supported yet");
    }

    default void addLong(LdbColumnFamily cf, Slice key, Slice value) {
      throw new UnsupportedOperationException("addLong is not supported yet");
    }
  }
}
