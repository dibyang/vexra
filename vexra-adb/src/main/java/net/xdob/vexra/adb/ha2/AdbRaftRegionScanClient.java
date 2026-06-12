package net.xdob.vexra.adb.ha2;

import com.google.protobuf.ByteString;
import net.xdob.vexra.adb.db.AdbRegionScanClient;
import net.xdob.vexra.adb.db.AdbRegionScanRequest;
import net.xdob.vexra.adb.db.CF;
import net.xdob.vexra.adb.db.RowCodec;
import net.xdob.vexra.adb.db.RowValue;
import net.xdob.vexra.adb.key.DataKey;
import net.xdob.vexra.adb.key.IndexKey;
import net.xdob.vexra.adb.key.VersionIndexKey;
import net.xdob.vexra.adb.key.VersionKey;
import net.xdob.vexra.cluster.region.KeyRange;
import net.xdob.vexra.cluster.sql.RegionQueryResult;
import net.xdob.vexra.cluster.sql.RegionScanTask;
import net.xdob.vexra.proto.adb.ColumnFamily;
import net.xdob.vexra.proto.adb.Direction;
import net.xdob.vexra.proto.adb.KvPair;
import net.xdob.vexra.proto.adb.ReadRequest;
import net.xdob.vexra.proto.adb.ReadResponse;
import net.xdob.vexra.proto.adb.Scan;
import net.xdob.vexra.proto.adb.ScanResult;
import net.xdob.vexra.util.Proto2Util;
import org.h2.value.Value;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 基于现有 ADB Raft read path 的 region scan client。
 *
 * <p>该 client 将 {@link AdbRegionScanRequest} 转换为 ADB proto `ReadRequest.Scan`，
 * 通过 {@link RClient} 拉取 region key range 内的版本 KV，并在 client 侧做最小
 * MVCC 可见版本归并。后续有专用 RegionScanTask proto 后，可把可见性解析下沉到
 * region 状态机内。</p>
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
    Set<String> visitedLogicalKeys = new LinkedHashSet<>();
    List<Map<String, Object>> rows = new ArrayList<>();
    long count = 0;
    int limit = task.getLimit();

    do {
      ScanResult page = sendScan(range, resumeKey);
      for (KvPair entry : page.getEntriesList()) {
        VersionKey versionKey = VersionKey.fromBytes(entry.getKey().toByteArray());
        DataKey dataKey = versionKey.toDataKey();
        byte[] logicalKey = dataKey.toBytes();
        if (!range.contains(logicalKey)) {
          continue;
        }
        RowValue value = RowValue.decodeValue(entry.getValue().toByteArray());
        if (!isVisibleVersion(request, versionKey, value)) {
          continue;
        }
        String logicalKeyHex = toHex(logicalKey);
        if (!visitedLogicalKeys.add(logicalKeyHex)) {
          continue;
        }
        if (!isReadable(value)) {
          continue;
        }
        count++;
        if (!request.isCountOnly()) {
          rows.add(toRow(versionKey, dataKey, value));
          if (limit > 0 && rows.size() >= limit) {
            return new RegionQueryResult(task.getRegionId(), rows, 0);
          }
        } else if (limit > 0 && count >= limit) {
          return new RegionQueryResult(task.getRegionId(), null, count);
        }
      }
      hasMore = page.getHasMore();
      resumeKey = page.getResumeKey().isEmpty() ? null
          : page.getResumeKey().toByteArray();
    } while (hasMore && resumeKey != null);

    return new RegionQueryResult(task.getRegionId(),
        request.isCountOnly() ? null : rows, request.isCountOnly() ? count : 0);
  }

  private ScanResult sendScan(KeyRange range, byte[] resumeKey)
      throws SQLException {
    Scan.Builder scan = Scan.newBuilder()
        .setCf(ColumnFamily.forNumber(CF.DEFAULT.getCfId()))
        .setDirection(Direction.DIR_FORWARD)
        .setLimit(pageSize);
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
    ReadRequest request = ReadRequest.newBuilder()
        .setDbName(dbName)
        .setScan(scan)
        .build();
    try {
      ReadResponse response = client.sendReadRequest(request);
      if (!response.getSuccess()) {
        throw toSQLException(response);
      }
      if (!response.hasScanResult()) {
        throw new SQLException("ADB raft scan response missing scanResult");
      }
      return response.getScanResult();
    } catch (SQLException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new SQLException("ADB raft region scan failed", e);
    }
  }

  private boolean isVisibleVersion(AdbRegionScanRequest request,
      VersionKey versionKey, RowValue value) {
    if (!versionKey.isCommited()) {
      return false;
    }
    return value != null && value.commitTs <= request.getStartTs();
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

  private static boolean isReadable(RowValue value) {
    return value != null
        && !value.deleted
        && value.payload != null
        && value.payload.length > 0;
  }

  private static Map<String, Object> toRow(VersionKey versionKey,
      DataKey dataKey, RowValue value) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("row_id", dataKey.getRowId());
    row.put("payload", decodePayload(value.payload));
    row.put("key_hex", toHex(dataKey.toBytes()));
    if (versionKey instanceof VersionIndexKey && dataKey instanceof IndexKey) {
      IndexKey indexKey = (IndexKey) dataKey;
      row.put("index_id", indexKey.getIndexId());
      row.put("index_hex", toHex(indexKey.getIndex()));
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
