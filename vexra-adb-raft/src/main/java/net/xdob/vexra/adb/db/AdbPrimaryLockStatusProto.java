package net.xdob.vexra.adb.db;

import com.google.protobuf.ByteString;
import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.proto.adb.PrimaryLockStatusRequest;
import net.xdob.vexra.proto.adb.PrimaryLockStatusResult;

import java.sql.SQLException;
import java.util.Objects;

/**
 * ADB primary lock 状态与 proto 消息之间的适配器。
 *
 * <p>该类让本地状态机、LocalRClient 和真实 Raft/RPC client 共享同一套
 * primary 状态查询语义：请求只携带 txnId 与 primary logical key，服务端继续复用
 * {@link LocalAdbPrimaryLockStatusReader} 判断该 primary 是否已经由同一事务提交。</p>
 */
public final class AdbPrimaryLockStatusProto {
  private static final String STATUS_REGION_ID = "primary-status";

  private AdbPrimaryLockStatusProto() {
  }

  /**
   * 把 durable lock 转换为 primary-status read request。
   *
   * @param lock secondary 或 primary lock 记录
   * @return primary-status read request
   */
  public static PrimaryLockStatusRequest toRequest(AdbTxnLock lock) {
    Objects.requireNonNull(lock, "lock == null");
    return PrimaryLockStatusRequest.newBuilder()
        .setTxnId(lock.getTxnId())
        .setPrimaryKey(ByteString.copyFrom(lock.getPrimaryKey()))
        .build();
  }

  /**
   * 在指定 store 上执行 primary-status 查询。
   *
   * @param store ADB store
   * @param request primary-status read request
   * @return primary-status read result
   * @throws SQLException 查询失败时抛出
   */
  public static PrimaryLockStatusResult read(DbStore store,
      PrimaryLockStatusRequest request) throws SQLException {
    Objects.requireNonNull(store, "store == null");
    Objects.requireNonNull(request, "request == null");
    if (request.getTxnId() < 0) {
      throw new SQLException("primary status txnId is negative: "
          + request.getTxnId());
    }
    if (request.getPrimaryKey().isEmpty()) {
      throw new SQLException("primary status primaryKey is empty");
    }
    byte[] primaryKey = request.getPrimaryKey().toByteArray();
    AdbTxnLock lock = new AdbTxnLock(request.getTxnId(), primaryKey,
        primaryKey, 1, STATUS_REGION_ID, 0);
    return toProto(new LocalAdbPrimaryLockStatusReader(store)
        .readPrimaryStatus(lock));
  }

  /**
   * 把内部 primary 状态转换为 proto result。
   *
   * @param status 内部 primary 状态
   * @return proto result
   */
  public static PrimaryLockStatusResult toProto(AdbPrimaryLockStatus status) {
    Objects.requireNonNull(status, "status == null");
    PrimaryLockStatusResult.Builder builder =
        PrimaryLockStatusResult.newBuilder()
            .setCommitted(status.isCommitted());
    if (status.isCommitted()) {
      builder.setCommitTs(status.getCommitTs());
    }
    return builder.build();
  }

  /**
   * 把 proto result 转换为内部 primary 状态。
   *
   * @param result proto result
   * @return 内部 primary 状态
   */
  public static AdbPrimaryLockStatus fromProto(
      PrimaryLockStatusResult result) {
    Objects.requireNonNull(result, "result == null");
    if (!result.getCommitted()) {
      return AdbPrimaryLockStatus.unknown();
    }
    return AdbPrimaryLockStatus.committed(result.getCommitTs());
  }
}
