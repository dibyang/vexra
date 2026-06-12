package net.xdob.vexra.adb.ha2;

import com.google.protobuf.ByteString;
import net.xdob.vexra.adb.db.AdbRegionScanClient;
import net.xdob.vexra.adb.db.AdbRegionScanRequest;
import net.xdob.vexra.adb.db.RowCodec;
import net.xdob.vexra.cluster.region.KeyRange;
import net.xdob.vexra.cluster.sql.RegionQueryResult;
import net.xdob.vexra.cluster.sql.RegionScanTask;
import net.xdob.vexra.proto.adb.ReadRequest;
import net.xdob.vexra.proto.adb.ReadResponse;
import net.xdob.vexra.proto.adb.RegionScan;
import net.xdob.vexra.proto.adb.RegionScanResult;
import net.xdob.vexra.proto.adb.RegionVisibleRow;
import net.xdob.vexra.util.Proto2Util;
import org.h2.value.Value;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 基于现有 ADB Raft read path 的 region scan client。
 *
 * <p>该 client 将 {@link AdbRegionScanRequest} 转换为 ADB proto
 * `ReadRequest.RegionScan`，由 region 状态机在读路径内完成最小 MVCC 可见性归并，
 * client 只负责分页、错误映射和结果对象适配。</p>
 */
public final class AdbRaftRegionScanClient implements AdbRegionScanClient {
  private static final int DEFAULT_PAGE_SIZE = 256;

  private final String dbName;
  private final RClient client;
  private final int pageSize;

  /**
   * 创建 ADB Raft region scan client。
   *
   * @param dbName ADB 数据库名
   * @param client ADB Raft client
   */
  public AdbRaftRegionScanClient(String dbName, RClient client) {
    this(dbName, client, DEFAULT_PAGE_SIZE);
  }

  /**
   * 创建 ADB Raft region scan client。
   *
   * @param dbName ADB 数据库名
   * @param client ADB Raft client
   * @param pageSize 单次 scan 拉取的原始版本 KV 数量
   */
  public AdbRaftRegionScanClient(String dbName, RClient client, int pageSize) {
    this.dbName = normalize(dbName, "dbName");
    this.client = Objects.requireNonNull(client, "client == null");
    if (pageSize <= 0) {
      throw new IllegalArgumentException("pageSize must be positive");
    }
    this.pageSize = pageSize;
  }

  @Override
  public CompletableFuture<RegionQueryResult> scanAsync(
      AdbRegionScanRequest request) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        return scan(request);
      } catch (SQLException e) {
        throw new CompletionException(e);
      }
    });
  }

  private RegionQueryResult scan(AdbRegionScanRequest request)
      throws SQLException {
    Objects.requireNonNull(request, "request == null");
    RegionScanTask task = request.getTask();
    KeyRange range = task.getKeyRange();
    byte[] resumeKey = null;
    boolean hasMore;
    List<Map<String, Object>> rows = new ArrayList<>();
    long count = 0;
    int limit = task.getLimit();

    do {
      RegionScanResult page = sendRegionScan(request, resumeKey,
          nextPageLimit(request, rows.size(), count));
      if (request.isCountOnly()) {
        count += page.getCount();
      } else {
        for (RegionVisibleRow row : page.getRowsList()) {
          rows.add(toRow(row));
        }
      }
      hasMore = page.getHasMore();
      resumeKey = page.getResumeKey().isEmpty() ? null
          : page.getResumeKey().toByteArray();
      if (hasMore && resumeKey == null) {
        throw new SQLException("ADB raft region scan missing resumeKey");
      }
      if (limitReached(request, rows.size(), count)) {
        break;
      }
    } while (hasMore && resumeKey != null);

    return new RegionQueryResult(task.getRegionId(),
        request.isCountOnly() ? null : rows, request.isCountOnly() ? count : 0);
  }

  private int nextPageLimit(AdbRegionScanRequest request, int rowCount,
      long count) {
    int queryLimit = request.getTask().getLimit();
    if (queryLimit <= 0) {
      return pageSize;
    }
    long used = request.isCountOnly() ? count : rowCount;
    long remaining = Math.max(0, queryLimit - used);
    return (int) Math.min(pageSize, remaining);
  }

  private boolean limitReached(AdbRegionScanRequest request, int rowCount,
      long count) {
    int queryLimit = request.getTask().getLimit();
    if (queryLimit <= 0) {
      return false;
    }
    return request.isCountOnly() ? count >= queryLimit : rowCount >= queryLimit;
  }

  private RegionScanResult sendRegionScan(AdbRegionScanRequest request,
      byte[] resumeKey, int limit)
      throws SQLException {
    RegionScanTask task = request.getTask();
    KeyRange range = task.getKeyRange();
    RegionScan.Builder scan = RegionScan.newBuilder()
        .setRegionId(task.getRegionId())
        .setTxnId(request.getTxnId())
        .setStartTs(request.getStartTs())
        .setCountOnly(request.isCountOnly())
        .setLimit(limit > 0 ? limit : pageSize);
    byte[] startKey = range.getStartKey();
    byte[] endKey = range.getEndKey();
    if (startKey.length > 0) {
      scan.setStartKey(ByteString.copyFrom(startKey));
    }
    if (endKey.length > 0) {
      scan.setEndKey(ByteString.copyFrom(endKey));
    }
    if (resumeKey != null && resumeKey.length > 0) {
      scan.setResumeKey(ByteString.copyFrom(resumeKey));
    }
    ReadRequest readRequest = ReadRequest.newBuilder()
        .setDbName(dbName)
        .setRegionScan(scan)
        .build();
    try {
      ReadResponse response = client.sendReadRequest(readRequest);
      if (!response.getSuccess()) {
        throw toSQLException(response);
      }
      if (!response.hasRegionScanResult()) {
        throw new SQLException(
            "ADB raft region scan response missing regionScanResult");
      }
      return response.getRegionScanResult();
    } catch (SQLException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new SQLException("ADB raft region scan failed", e);
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
    return new SQLException("ADB raft scan failed");
  }

  private static Map<String, Object> toRow(RegionVisibleRow visibleRow) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("row_id", visibleRow.getRowId());
    row.put("payload", decodePayload(visibleRow.getPayload().toByteArray()));
    row.put("key_hex", toHex(visibleRow.getKey().toByteArray()));
    if (visibleRow.getIndexRow()) {
      row.put("index_id", visibleRow.getIndexId());
      row.put("index_hex", toHex(visibleRow.getIndex().toByteArray()));
    }
    return row;
  }

  private static Object decodePayload(byte[] payload) {
    Value value = RowCodec.decode(payload);
    return value.getString();
  }

  private static String toHex(byte[] bytes) {
    StringBuilder builder = new StringBuilder();
    for (byte b : bytes) {
      builder.append(String.format("%02x", b & 0xff));
    }
    return builder.toString();
  }

  private static String normalize(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return value.trim();
  }
}
