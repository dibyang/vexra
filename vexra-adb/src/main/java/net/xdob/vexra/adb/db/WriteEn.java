package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.util.Utils;

import java.util.Optional;

public class WriteEn {
  private final byte cfId;
  private final OP op;
  private final byte[] key;
  private final byte[] value;

  public WriteEn(byte cfId, OP op, byte[] key, byte[] value) {
    this.cfId = cfId;
    this.op = op;
    this.key = key;
    this.value = value;
  }

  public byte getCfId() {
    return cfId;
  }

  public OP getOp() {
    return op;
  }

  public byte[] getKey() {
    return key;
  }

  public byte[] getValue() {
    return value;
  }

  public Optional<Long> getLongValue() {
    return Utils.decodeLong(value);
  }

  public static WriteEn of(byte cfId, OP op, byte[] key, byte[] value) {
    return new WriteEn(cfId, op, key, value);
  }


  public static WriteEn put(byte[] key, byte[] value) {
    return of(CF.DEFAULT.getCfId(), OP.PUT, key, value);
  }

  public static WriteEn delete(byte[] key) {
    return of(CF.DEFAULT.getCfId(), OP.DELETE, key, null);
  }

  public static WriteEn deleteRange(byte[] beginKey, byte[] endKey) {
    return of(CF.DEFAULT.getCfId(), OP.DELETE_RANGE, beginKey, endKey);
  }

//  public static WriteEn addLong(byte[] key, long value) {
//    return of(CF.DEFAULT.getCfId(), OP.ADD_LONG, key, Utils.encodeLong(value));
//  }

  public static WriteEn put(byte cfId, byte[] key, byte[] value) {
    return of(cfId, OP.PUT, key, value);
  }

  public static WriteEn delete(byte cfId, byte[] key) {
    return of(cfId, OP.DELETE, key, null);
  }

  public static WriteEn deleteRange(byte cfId, byte[] beginKey, byte[] endKey) {
    return of(cfId, OP.DELETE_RANGE, beginKey, endKey);
  }

//  public static WriteEn addLong(byte cfId, byte[] key, long value) {
//    return of(cfId, OP.ADD_LONG, key, Utils.encodeLong(value));
//  }


}
