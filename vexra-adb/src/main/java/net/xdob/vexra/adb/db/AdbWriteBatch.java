package net.xdob.vexra.adb.db;


import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.adb.util.Utils;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ADB 写批次适配器。
 *
 * <p>默认模式会收集 {@link WriteEn}，供 Raft/远端复制路径把写入序列化成协议消息。
 * 本地 LDB/Rocks 路径可以使用 direct delegate 模式，把写入直接落到底层 native
 * write batch，避免 commit / bulk insert 热路径额外创建 WriteEn 中间对象。</p>
 */
public class AdbWriteBatch {

  private final DbStore store;
  private final DelegateWriteBatch directDelegate;
  private final List<WriteEn> entries;
  private int directCount;


  public AdbWriteBatch(DbStore store) {
    this.store = store;
    this.directDelegate = null;
    this.entries = new ArrayList<>();
  }

  /**
   * 创建直写底层 delegate 的批次。
   *
   * @param store 当前 store
   * @param directDelegate 底层 native write batch 适配器
   * @return 直写模式批次
   */
  public static AdbWriteBatch direct(DbStore store,
      DelegateWriteBatch directDelegate) {
    return new AdbWriteBatch(store, directDelegate);
  }

  private AdbWriteBatch(DbStore store, DelegateWriteBatch directDelegate) {
    this.store = store;
    this.directDelegate = directDelegate;
    this.entries = directDelegate == null ? new ArrayList<>()
        : Collections.emptyList();
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
    if (directDelegate != null) {
      direct(() -> directDelegate.put(key, value));
      return;
    }
    entries.add(WriteEn.put(key, value));
  }

  public void putLong(byte[] key, long value) {
    put(key, Utils.encodeLong( value));
  }

  public void delete(byte[] key) {
    if (directDelegate != null) {
      direct(() -> directDelegate.delete(key));
      return;
    }
    entries.add(WriteEn.delete(key));
  }
  public void deleteRange(byte[] beginKey, byte[] endKey){
    if (directDelegate != null) {
      direct(() -> directDelegate.deleteRange(beginKey, endKey));
      return;
    }
    entries.add(WriteEn.deleteRange(beginKey, endKey));
  }
//  public void addLong(byte[] key, long value) {
//    entries.add(WriteEn.addLong(key, value));
//  }

  public void put(byte cfId, byte[] key, byte[] value) {
    if (directDelegate != null) {
      direct(() -> directDelegate.put(CF.of(cfId), key, value));
      return;
    }
    entries.add(WriteEn.put(cfId, key, value));
  }
  public void putLong(byte cfId, byte[] key, long value){
    put(cfId, key, Utils.encodeLong( value));
  }
  public void delete(byte cfId, byte[] key) {
    if (directDelegate != null) {
      direct(() -> directDelegate.delete(CF.of(cfId), key));
      return;
    }
    entries.add(WriteEn.delete(cfId, key));
  }
  public void deleteRange(byte cfId, byte[] beginKey, byte[] endKey){
    if (directDelegate != null) {
      direct(() -> directDelegate.deleteRange(CF.of(cfId), beginKey, endKey));
      return;
    }
    entries.add(WriteEn.deleteRange(cfId, beginKey, endKey));
  }

//  public void addLong(byte cfId, byte[] key, long value) {
//    entries.add(WriteEn.addLong(cfId, key, value));
//  }

  public int count() {
    return directDelegate == null ? entries.size() : directCount;
  }

  public void clear() {
    if (directDelegate != null) {
      throw new UnsupportedOperationException(
          "Direct write batch cannot be cleared after native writes");
    }
    entries.clear();
  }
  
  // =========================
  // 鎵归噺鍐欏叆
  // =========================

  public void writeTo(DelegateWriteBatch batch) throws SQLException {
    if (directDelegate != null) {
      throw new IllegalStateException(
          "Direct write batch has already written to its delegate");
    }
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

  private void direct(SqlRunnable runnable) {
    try {
      runnable.run();
      directCount++;
    } catch (SQLException e) {
      throw new DirectWriteBatchException(e);
    }
  }

  @FunctionalInterface
  private interface SqlRunnable {
    void run() throws SQLException;
  }

  /**
   * 直写模式把 checked SQLException 暂存为运行时异常，外层 store 会还原为 SQLException。
   */
  public static final class DirectWriteBatchException extends RuntimeException {
    public DirectWriteBatchException(SQLException cause) {
      super(cause);
    }

    @Override
    public synchronized SQLException getCause() {
      return (SQLException) super.getCause();
    }
  }

}
