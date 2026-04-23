package net.xdob.vexra.ldb;

import net.xdob.vexra.ldb.util.Slice;

import java.util.Objects;

public final class WriteOp {
  private final LdbColumnFamily cf;
  private final OP type;
  private final Slice key;
  private final Slice value;
  private final Slice endKey;

  private WriteOp(LdbColumnFamily cf, OP type, Slice key, Slice value, Slice endKey) {
    this.cf = Objects.requireNonNull(cf, "cf");
    this.type = type;
    this.key = key;
    this.value = value;
    this.endKey = endKey;
  }

  private WriteOp(OP type, Slice key, Slice value, Slice endKey) {
    this(LdbColumnFamily.DEFAULT, type, key, value, endKey);
  }

  public static WriteOp put(LdbColumnFamily cf, Slice key, Slice value) {
    return new WriteOp(cf, OP.PUT, key, value, null);
  }

  public static WriteOp delete(LdbColumnFamily cf, Slice key) {
    return new WriteOp(cf, OP.DELETE, key, null, null);
  }

  public static WriteOp deleteRange(LdbColumnFamily cf, Slice beginKey, Slice endKey) {
    return new WriteOp(cf, OP.DELETE_RANGE, beginKey, null, endKey);
  }

  public static WriteOp addLong(LdbColumnFamily cf, Slice key, Slice delta) {
    return new WriteOp(cf, OP.ADD_LONG, key, delta, null);
  }

  public static WriteOp put(Slice key, Slice value) {
    return new WriteOp(OP.PUT, key, value, null);
  }

  public static WriteOp delete(Slice key) {
    return new WriteOp(OP.DELETE, key, null, null);
  }

  public static WriteOp deleteRange(Slice beginKey, Slice endKey) {
    return new WriteOp(OP.DELETE_RANGE, beginKey, null, endKey);
  }

  public static WriteOp addLong(Slice key, Slice value) {
    return new WriteOp(OP.ADD_LONG, key, value, null);
  }

  public LdbColumnFamily getCf() {
    return cf;
  }

  public OP getType() {
    return type;
  }

  public Slice getKey() {
    return key;
  }

  public Slice getValue() {
    return value;
  }

  public Slice getEndKey() {
    return endKey;
  }
}
