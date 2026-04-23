package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.DataKey;
import net.xdob.vexra.adb.key.Key;
import net.xdob.vexra.adb.key.PrefixKey;

import java.sql.SQLException;

/**
 * 版本解析器接口，用于处理数据库中的多版本并发控制（MVCC）
 * 提供获取已提交版本、可见版本以及范围查询的功能
 */
public interface VersionResolver {
  /**
   * 获取指定键的最新已提交版本
   *
   * @param key 数据键
   * @return 最新已提交的行值，如果不存在则返回null
   * @throws SQLException 数据库访问异常
   */
  RowValue getLatestCommitted(Key key) throws SQLException;

  /**
   * 获取事务可见的最新版本数据
   *
   * @param txn 当前事务上下文
   * @param key 数据键
   * @return 事务可见的行值，如果不存在则返回null
   */
  RowValue getVisible(Transaction2 txn, DataKey key) ;

  /**
   * 获取指定时间戳之前最新已提交的版本
   *
   * @param key 数据键
   * @param startTs 起始时间戳
   * @return 指定时间戳前最新已提交的行值，如果不存在则返回null
   * @throws SQLException 数据库访问异常
   */
  RowValue getLatestCommittedBefore(DataKey key, long startTs) throws SQLException;

  /**
   * 获取指定前缀键范围内的第一个键值对
   *
   * @param txn 当前事务上下文
   * @param prefixKey 前缀键，用于范围查询
   * @return 范围内的第一个行值，如果不存在则返回null
   * @throws SQLException 数据库访问异常
   */
  RowValue first(Transaction2 txn, PrefixKey prefixKey) throws SQLException;

  /**
   * 获取指定前缀键范围内的最后一个键值对
   *
   * @param txn 当前事务上下文
   * @param prefixKey 前缀键，用于范围查询
   * @return 范围内的最后一个行值，如果不存在则返回null
   * @throws SQLException 数据库访问异常
   */
  RowValue last(Transaction2 txn, PrefixKey prefixKey) throws SQLException;
}
