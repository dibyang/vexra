package net.xdob.vexra.adb.rocks;


import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.adb.db.CF;
import net.xdob.vexra.adb.db.DelegateWriteBatch;
import net.xdob.vexra.adb.util.Utils;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;

import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * WriteBatch 的包装类：
 * 1. 对外暴露原有常用能力，并委托给内部 delegate
 * 2. 提供一些额外便捷方法
 *
 * 注意：
 * - 这里采用组合而不是继承，避免碰 JNI/nativeHandle 生命周期问题
 * - 你可以按自己的项目习惯继续补充更多便捷方法
 */
public class DelegateRocksWriteBatch implements DelegateWriteBatch {

  private final WriteBatch delegate;
  private final DbStore store;
  private final StoreCF storeCF;


  public DelegateRocksWriteBatch(WriteBatch delegate, DbStore store, StoreCF storeCF) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.store = store;
    this.storeCF = storeCF;
  }

  /**
   * 暴露底层原始对象，必要时可直接用。
   */
  public WriteBatch unwrap() {
    return delegate;
  }

  public DbStore getStore() {
    return store;
  }

  // =========================
  // 扩展能力
  // =========================


  public byte[] get(byte[] key) throws SQLException {
    return store.get(key);
  }


  @Override
  public void put(CF cf, byte[] key, byte[] value) throws SQLException {
    try {
      delegate.put(storeCF.getCFHandle(cf), key, value);
    } catch (RocksDBException e) {
      throw RocksDBUtil.convert(e);
    }
  }

  @Override
  public void addLong(CF cf, byte[] key, long delta) throws SQLException {
    try {
      delegate.merge(storeCF.getCFHandle(cf), key, Utils.encodeLong(delta));
    } catch (RocksDBException e) {
      throw RocksDBUtil.convert(e);
    }
  }

  @Override
  public void delete(CF cf, byte[] key) throws SQLException {
    try {
      delegate.delete(storeCF.getCFHandle(cf), key);
    } catch (RocksDBException e) {
      throw RocksDBUtil.convert(e);
    }
  }

  @Override
  public void deleteRange(CF cf, byte[] beginKey, byte[] endKey) throws SQLException {
    try {
      delegate.deleteRange(storeCF.getCFHandle(cf), beginKey, endKey);
    } catch (RocksDBException e) {
      throw RocksDBUtil.convert(e);
    }
  }

  public void addLong(byte[] key, long delta) throws SQLException{
    try {
      delegate.merge(key, Utils.encodeLong( delta));
    } catch (RocksDBException e) {
      throw RocksDBUtil.convert(e);
    }
  }


  public void iterate(WriteBatch.Handler handler) throws RocksDBException {
    delegate.iterate(handler);
  }

  public byte[] data() throws RocksDBException {
    return delegate.data();
  }

  public long getDataSize() {
    return delegate.getDataSize();
  }

  public boolean hasPut() {
    return delegate.hasPut();
  }

  public boolean hasDelete() {
    return delegate.hasDelete();
  }

  public boolean hasSingleDelete() {
    return delegate.hasSingleDelete();
  }

  public boolean hasDeleteRange() {
    return delegate.hasDeleteRange();
  }

  public boolean hasMerge() {
    return delegate.hasMerge();
  }

  public boolean hasBeginPrepare() {
    return delegate.hasBeginPrepare();
  }

  public boolean hasEndPrepare() {
    return delegate.hasEndPrepare();
  }

  public boolean hasCommit() {
    return delegate.hasCommit();
  }

  public boolean hasRollback() {
    return delegate.hasRollback();
  }

  public void markWalTerminationPoint() {
    delegate.markWalTerminationPoint();
  }

  public WriteBatch.SavePoint getWalTerminationPoint() {
    return delegate.getWalTerminationPoint();
  }

  public int count() {
    return delegate.count();
  }

  public void clear() {
    delegate.clear();
  }

  public void setSavePoint() {
    delegate.setSavePoint();
  }

  public void rollbackToSavePoint() throws SQLException {
    try {
      delegate.rollbackToSavePoint();
    } catch (RocksDBException e) {
      throw new RuntimeException(e);
    }
  }

  public void popSavePoint() throws SQLException {
    try {
      delegate.popSavePoint();
    } catch (RocksDBException e) {
      throw new RuntimeException(e);
    }
  }

  public void setMaxBytes(long maxBytes) {
    delegate.setMaxBytes(maxBytes);
  }

  public void put(byte[] key, byte[] value) throws SQLException {
    try {
      delegate.put(key, value);
    } catch (RocksDBException e) {
      throw RocksDBUtil.convert(e);
    }
  }

  public void put(ColumnFamilyHandle cf, byte[] key, byte[] value) throws SQLException {
    try {
      delegate.put(cf, key, value);
    } catch (RocksDBException e) {
      throw RocksDBUtil.convert(e);
    }
  }

  public void merge(byte[] key, byte[] value) throws SQLException {
    try {
      delegate.merge(key, value);
    } catch (RocksDBException e) {
      throw RocksDBUtil.convert(e);
    }
  }

  public void merge(ColumnFamilyHandle cf, byte[] key, byte[] value) throws SQLException {
    try {
      delegate.merge(cf, key, value);
    } catch (RocksDBException e) {
      throw RocksDBUtil.convert(e);
    }
  }

  public void delete(byte[] key) throws SQLException {
    try {
      delegate.delete(key);
    } catch (RocksDBException e) {
      throw RocksDBUtil.convert(e);
    }
  }

  public void delete(ColumnFamilyHandle cf, byte[] key) throws SQLException {
    try {
      delegate.delete(cf, key);
    } catch (RocksDBException e) {
      throw RocksDBUtil.convert(e);
    }
  }

  public void singleDelete(byte[] key) throws SQLException {
    try {
      delegate.singleDelete(key);
    } catch (RocksDBException e) {
      throw RocksDBUtil.convert(e);
    }
  }

  public void singleDelete(ColumnFamilyHandle cf, byte[] key) throws SQLException {
    try {
      delegate.singleDelete(cf, key);
    } catch (RocksDBException e) {
      throw RocksDBUtil.convert(e);
    }
  }

  public void deleteRange(byte[] beginKey, byte[] endKey) throws SQLException {
    try {
      delegate.deleteRange(beginKey, endKey);
    } catch (RocksDBException e) {
      throw RocksDBUtil.convert(e);
    }
  }

  public void deleteRange(ColumnFamilyHandle cf, byte[] beginKey, byte[] endKey) throws SQLException {
    try {
      delegate.deleteRange(cf, beginKey, endKey);
    } catch (RocksDBException e) {
      throw RocksDBUtil.convert(e);
    }
  }

  public void putLogData(byte[] blob) throws SQLException {
    try {
      delegate.putLogData(blob);
    } catch (RocksDBException e) {
      throw RocksDBUtil.convert(e);
    }
  }


  @Override
  public void close() {
    delegate.close();
  }

  /**
   * 支持链式构建。
   */
  public DelegateRocksWriteBatch apply(Consumer<DelegateRocksWriteBatch> consumer) {
    consumer.accept(this);
    return this;
  }

}
