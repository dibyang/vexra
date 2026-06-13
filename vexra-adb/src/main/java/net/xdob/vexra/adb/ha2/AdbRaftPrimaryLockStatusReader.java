package net.xdob.vexra.adb.ha2;

import net.xdob.vexra.adb.db.AdbPrimaryLockStatus;
import net.xdob.vexra.adb.db.AdbPrimaryLockStatusProto;
import net.xdob.vexra.adb.db.AdbPrimaryLockStatusReader;
import net.xdob.vexra.adb.db.AdbTxnLock;
import net.xdob.vexra.proto.adb.ReadRequest;
import net.xdob.vexra.proto.adb.ReadResponse;
import net.xdob.vexra.util.Proto2Util;

import java.sql.SQLException;
import java.util.Objects;

/**
 * 基于 ADB Raft read path 的 primary lock 状态读取器。
 *
 * <p>该实现把 resolver 的 primary 状态查询转换成
 * `ReadRequest.PrimaryLockStatus`，由 primary 所在 region 的状态机读取 committed
 * version 并返回是否已由同一事务提交。调用方可以把它注入
 * `AdbLockResolver`，用于跨 region secondary lock 前滚判断。</p>
 */
public final class AdbRaftPrimaryLockStatusReader
    implements AdbPrimaryLockStatusReader {
  private final String dbName;
  private final RClient client;

  /**
   * 创建 Raft primary lock 状态读取器。
   *
   * @param dbName ADB 数据库名
   * @param client ADB Raft client
   */
  public AdbRaftPrimaryLockStatusReader(String dbName, RClient client) {
    this.dbName = normalize(dbName, "dbName");
    this.client = Objects.requireNonNull(client, "client == null");
  }

  @Override
  public AdbPrimaryLockStatus readPrimaryStatus(AdbTxnLock lock)
      throws SQLException {
    Objects.requireNonNull(lock, "lock == null");
    ReadRequest request = ReadRequest.newBuilder()
        .setDbName(dbName)
        .setPrimaryLockStatus(AdbPrimaryLockStatusProto.toRequest(lock))
        .build();
    try {
      ReadResponse response = client.sendReadRequest(request);
      if (!response.getSuccess()) {
        throw toSQLException(response);
      }
      if (!response.hasPrimaryLockStatusResult()) {
        throw new SQLException(
            "ADB primary-status response missing result");
      }
      return AdbPrimaryLockStatusProto.fromProto(
          response.getPrimaryLockStatusResult());
    } catch (SQLException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new SQLException("ADB primary-status read failed", e);
    }
  }

  private static SQLException toSQLException(ReadResponse response) {
    if (response.hasEx()) {
      try {
        Throwable throwable = Proto2Util.toThrowable(response.getEx(),
            Throwable.class);
        if (throwable instanceof SQLException) {
          return (SQLException) throwable;
        }
        return new SQLException(throwable.getMessage(), throwable);
      } catch (RuntimeException e) {
        return new SQLException(response.getEx().getErrorMessage(), e);
      }
    }
    return new SQLException("ADB primary-status read failed");
  }

  private static String normalize(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return value.trim();
  }
}
