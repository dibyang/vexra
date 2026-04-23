package net.xdob.vexra.adb.ldb;


import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.adb.db.CF;
import net.xdob.vexra.adb.db.DelegateWriteBatch;
import net.xdob.vexra.ldb.LdbWriteBatch;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Objects;

/**
 * WriteBatch 的包装类：
 * 1. 对外暴露原有常用能力，并委托给内部 delegate
 * 2. 提供一些额外便捷方法
 *
 * 注意：
 * - 这里采用组合而不是继承，避免碰 JNI/nativeHandle 生命周期问题
 * - 你可以按自己的项目习惯继续补充更多便捷方法
 */
public class DelegateLdbWriteBatch implements DelegateWriteBatch {

  private final LdbWriteBatch delegate;
  private final DbStore store;
  private final LdbCF ldbCF;


  public DelegateLdbWriteBatch(LdbWriteBatch delegate, DbStore store, LdbCF ldbCF) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.store = store;
    this.ldbCF = ldbCF;
  }


  @Override
  public DbStore getStore() {
    return store;
  }

  // =========================
  // 扩展能力
  // =========================


  @Override
  public byte[] get(byte[] key) throws SQLException {
    return store.get(key);
  }

  @Override
  public void put(byte[] key, byte[] value) throws SQLException {
    delegate.put(key, value);
  }

  @Override
  public void addLong(byte[] key, long delta) throws SQLException{
    delegate.addLong(key, delta);
  }

  @Override
  public void put(CF cf, byte[] key, byte[] value) throws SQLException {
    delegate.put(ldbCF.getCFHandle(cf), key, value);
  }

  @Override
  public void addLong(CF cf, byte[] key, long delta) throws SQLException {
    delegate.addLong(ldbCF.getCFHandle(cf), key, delta);
  }

  @Override
  public void delete(byte[] key) throws SQLException {
    delegate.delete(key);
  }

  @Override
  public void delete(CF cf, byte[] key) throws SQLException {
    delegate.delete(ldbCF.getCFHandle(cf), key);
  }

  @Override
  public void deleteRange(byte[] beginKey, byte[] endKey) throws SQLException {
    delegate.deleteRange(beginKey, endKey);
  }

  @Override
  public void deleteRange(CF cf, byte[] beginKey, byte[] endKey) throws SQLException {
    delegate.deleteRange(ldbCF.getCFHandle(cf), beginKey, endKey);
  }

  @Override
  public void rollbackToSavePoint() throws SQLException {
    //delegate.rollbackToSavePoint();
  }

  @Override
  public void popSavePoint() throws SQLException {
    //delegate.popSavePoint();
  }

  @Override
  public void setMaxBytes(long maxBytes) {
    //delegate.setMaxBytes(maxBytes);
  }





  @Override
  public void close() throws IOException {
    delegate.close();
  }



}
