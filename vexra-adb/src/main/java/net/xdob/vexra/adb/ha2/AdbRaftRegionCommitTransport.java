package net.xdob.vexra.adb.ha2;

import com.google.protobuf.ByteString;
import net.xdob.vexra.adb.db.AdbRegionCommitPhase;
import net.xdob.vexra.adb.db.AdbRegionCommitRequest;
import net.xdob.vexra.adb.db.AdbRegionCommitResponse;
import net.xdob.vexra.adb.db.AdbRegionCommitTransport;
import net.xdob.vexra.adb.db.Meta;
import net.xdob.vexra.proto.adb.Batch;
import net.xdob.vexra.proto.adb.Commit;
import net.xdob.vexra.proto.adb.MetaProto;
import net.xdob.vexra.proto.adb.Rollback;
import net.xdob.vexra.proto.adb.WriteRequest;
import net.xdob.vexra.proto.adb.WriteResponse;
import net.xdob.vexra.util.Proto2Util;

import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 基于现有 ADB Raft client 的 region commit transport。
 *
 * <p>该 transport 把 region 2PC 阶段请求转换为 ADB proto `WriteRequest`。当前 proto
 * 尚未提供独立 prewrite 消息，因此 PREWRITE 先通过空 batch 走一次 Raft 写路径作为阶段
 * fencing；COMMIT 和 ROLLBACK 分别映射到已有 `Commit` / `Rollback` 消息。后续补齐
 * MVCC lock column 后，可以在该类内替换 PREWRITE 的具体消息，而不影响上层
 * `AdbRpcRegionCommitClient`。</p>
 */
public final class AdbRaftRegionCommitTransport
    implements AdbRegionCommitTransport {
  private final String dbName;
  private final RClient client;

  /**
   * 创建 ADB Raft region commit transport。
   *
   * @param dbName ADB 数据库名
   * @param client 现有 ADB Raft client
   */
  public AdbRaftRegionCommitTransport(String dbName, RClient client) {
    this.dbName = normalize(dbName, "dbName");
    this.client = Objects.requireNonNull(client, "client == null");
  }

  @Override
  public CompletableFuture<AdbRegionCommitResponse> sendAsync(
      AdbRegionCommitPhase phase, AdbRegionCommitRequest request) {
    Objects.requireNonNull(phase, "phase == null");
    Objects.requireNonNull(request, "request == null");
    WriteRequest writeRequest = toWriteRequest(phase, request);
    return client.sendWriteRequestAsync(writeRequest)
        .handle((response, error) -> {
          if (error != null) {
            Throwable cause = unwrap(error);
            return AdbRegionCommitResponse.failure(phase,
                request.getRegionId(), cause.getMessage(), cause);
          }
          return toRegionResponse(phase, request, response);
        });
  }

  private WriteRequest toWriteRequest(AdbRegionCommitPhase phase,
      AdbRegionCommitRequest request) {
    WriteRequest.Builder builder = WriteRequest.newBuilder().setDbName(dbName);
    if (phase == AdbRegionCommitPhase.PREWRITE) {
      // 当前 proto 没有独立 prewrite。空 batch 让请求经过 Raft 写路径，保留阶段 fencing。
      return builder.setBatch(Batch.newBuilder().build()).build();
    }
    if (phase == AdbRegionCommitPhase.COMMIT) {
      Commit.Builder commit = Commit.newBuilder()
          .setTxnId(request.getTxnId())
          .setCommitTs(request.getCommitTs());
      for (Meta meta : request.getMetas()) {
        commit.addMetas(MetaProto.newBuilder()
            .setKey(ByteString.copyFrom(meta.getKey()))
            .setValue(ByteString.copyFrom(meta.getValue())));
      }
      return builder.setCommit(commit).build();
    }
    if (phase == AdbRegionCommitPhase.ROLLBACK) {
      return builder.setRollback(Rollback.newBuilder()
          .setTxnId(request.getTxnId())).build();
    }
    throw new IllegalArgumentException("unsupported phase: " + phase);
  }

  private AdbRegionCommitResponse toRegionResponse(AdbRegionCommitPhase phase,
      AdbRegionCommitRequest request, WriteResponse response) {
    if (response == null) {
      return AdbRegionCommitResponse.failure(phase, request.getRegionId(),
          "write response is null", null);
    }
    if (response.getSuccess()) {
      return AdbRegionCommitResponse.success(phase, request.getRegionId());
    }
    Throwable cause = null;
    String message = "raft write failed";
    if (response.hasEx()) {
      try {
        cause = Proto2Util.toThrowable(response.getEx(), Throwable.class);
        if (cause.getMessage() != null && !cause.getMessage().trim().isEmpty()) {
          message = cause.getMessage();
        }
      } catch (RuntimeException e) {
        message = response.getEx().getErrorMessage();
        cause = e;
      }
    }
    return AdbRegionCommitResponse.failure(phase, request.getRegionId(),
        message, cause == null ? new SQLException(message) : cause);
  }

  private static Throwable unwrap(Throwable error) {
    Throwable current = error;
    while (current instanceof CompletionException && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private static String normalize(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return value.trim();
  }
}
