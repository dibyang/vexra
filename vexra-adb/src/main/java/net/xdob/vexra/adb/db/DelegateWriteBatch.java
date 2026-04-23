package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.DbStore;

import java.sql.SQLException;

public interface DelegateWriteBatch extends AutoCloseable {
  DbStore getStore();

  byte[] get(byte[] key) throws SQLException;
  void put(byte[] key, byte[] value) throws SQLException;

  void put(CF cf, byte[] key, byte[] value) throws SQLException;

  void addLong(byte[] key, long delta) throws SQLException;

  void addLong(CF cf, byte[] key, long delta) throws SQLException;

  void delete(byte[] key) throws SQLException;

  void delete(CF cf, byte[] key) throws SQLException;

  void deleteRange(byte[] beginKey, byte[] endKey) throws SQLException;

  void deleteRange(CF cf, byte[] beginKey, byte[] endKey) throws SQLException;

  void rollbackToSavePoint() throws SQLException;

  void popSavePoint() throws SQLException;

  void setMaxBytes(long maxBytes);



}
