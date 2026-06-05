package net.xdob.vexra.adb.db;


import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.adb.util.Utils;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AdbWriteBatch {

  private final DbStore store;
  private final List<WriteEn> entries = new ArrayList<>();


  public AdbWriteBatch(DbStore store) {
    this.store = store;
  }

  public List<WriteEn> getEntries() {
    return Collections.unmodifiableList(entries);
  }

  public DbStore getStore() {
    return store;
  }

  // =========================
  // 鎵╁睍鑳藉姏
  // =========================

  public void put(byte[] key, byte[] value) {
    entries.add(WriteEn.put(key, value));
  }

  public void putLong(byte[] key, long value) {
    put(key, Utils.encodeLong( value));
  }

  public void delete(byte[] key) {
    entries.add(WriteEn.delete(key));
  }
  public void deleteRange(byte[] beginKey, byte[] endKey){
    entries.add(WriteEn.deleteRange(beginKey, endKey));
  }
//  public void addLong(byte[] key, long value) {
//    entries.add(WriteEn.addLong(key, value));
//  }

  public void put(byte cfId, byte[] key, byte[] value) {
    entries.add(WriteEn.put(cfId, key, value));
  }
  public void putLong(byte cfId, byte[] key, long value){
    put(cfId, key, Utils.encodeLong( value));
  }
  public void delete(byte cfId, byte[] key) {
    entries.add(WriteEn.delete(cfId, key));
  }
  public void deleteRange(byte cfId, byte[] beginKey, byte[] endKey){
    entries.add(WriteEn.deleteRange(cfId, beginKey, endKey));
  }

//  public void addLong(byte cfId, byte[] key, long value) {
//    entries.add(WriteEn.addLong(cfId, key, value));
//  }

  public int count() {
    return entries.size();
  }

  public void clear() {
    entries.clear();
  }
  
  // =========================
  // 鎵归噺鍐欏叆
  // =========================

  public void writeTo(DelegateWriteBatch batch) throws SQLException {
    for (WriteEn entry : entries) {
      CF cf = CF.of(entry.getCfId());
      switch (entry.getOp()) {
        case PUT:
          batch.put(cf, entry.getKey(), entry.getValue());
          break;
        case DELETE:
          batch.delete(cf, entry.getKey());
          break;
        case DELETE_RANGE:
          batch.deleteRange(cf, entry.getKey(), entry.getValue());
          break;
//        case ADD_LONG:
//          batch.addLong(cf, entry.getKey(), entry.getLongValue()
//              .orElseThrow(() -> new IllegalArgumentException("No value")));
//          break;
        default:
          throw new IllegalArgumentException("Unsupported operation: " + entry.getOp());
      }
    }
  }

}
