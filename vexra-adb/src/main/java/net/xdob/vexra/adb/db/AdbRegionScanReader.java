package net.xdob.vexra.adb.db;

import com.google.protobuf.ByteString;
import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.adb.key.DataKey;
import net.xdob.vexra.adb.key.IndexKey;
import net.xdob.vexra.adb.key.VersionIndexKey;
import net.xdob.vexra.adb.key.VersionKey;
import net.xdob.vexra.proto.adb.RegionScan;
import net.xdob.vexra.proto.adb.RegionScanResult;
import net.xdob.vexra.proto.adb.RegionVisibleRow;

import java.sql.SQLException;
import java.util.Objects;

/**
 * ADB region scan 读路径执行器。
 *
 * <p>该类服务于专用 `ReadRequest.RegionScan` proto：它在 region 状态机内扫描
 * DEFAULT CF 的版本 key，按 logical key 分组解析最小 MVCC 可见版本，并返回可见行
 * 或 count。它不负责 SQL filter/projection，也不负责跨 region 合并。</p>
 */
public final class AdbRegionScanReader {
  private AdbRegionScanReader() {
  }

  /**
   * 执行一次 region scan 分页。
   *
   * @param store ADB store
   * @param request region scan proto 请求
   * @return region scan proto 结果
   * @throws SQLException 当底层扫描失败或 key/value 解码失败时抛出
   */
  public static RegionScanResult scan(DbStore store, RegionScan request)
      throws SQLException {
    Objects.requireNonNull(store, "store == null");
    Objects.requireNonNull(request, "request == null");
    RegionScanResult.Builder result = RegionScanResult.newBuilder();
    byte[] startKey = request.getStartKey().isEmpty() ? null
        : request.getStartKey().toByteArray();
    byte[] endKey = request.getEndKey().isEmpty() ? null
        : request.getEndKey().toByteArray();
    byte[] resumeKey = request.getResumeKey().isEmpty() ? null
        : request.getResumeKey().toByteArray();
    int limit = request.getLimit() > 0 ? request.getLimit() : 256;
    long visibleCount = 0;
    byte[] lastConsumedKey = null;

    VersionScanSource scanSource = store.openVersionScanSource(
        CF.DEFAULT.getCfId(), ScanDirection.FORWARD);
    try {
      scanSource.seekToRangeStart(resumeKey != null ? resumeKey : startKey,
          endKey);
      if (resumeKey != null && scanSource.isValid()
          && KeyCodec.equals(scanSource.key(), resumeKey)) {
        scanSource.advance();
      }

      while (scanSource.isValid()) {
        VersionKey firstVersion = VersionKey.fromBytes(scanSource.key());
        DataKey dataKey = firstVersion.toDataKey();
        byte[] logicalKey = dataKey.toBytes();
        if (!contains(logicalKey, startKey, endKey)) {
          break;
        }

        VisibleVersion visible = consumeLogicalKey(scanSource, logicalKey,
            request.getStartTs());
        lastConsumedKey = visible.lastConsumedKey;
        if (isReadable(visible.value)) {
          visibleCount++;
          if (!request.getCountOnly()) {
            result.addRows(toRegionVisibleRow(visible.versionKey, dataKey,
                visible.value));
          }
          if (visibleCount >= limit) {
            break;
          }
        }
      }

      boolean hasMore = scanSource.isValid()
          && nextKeyInRange(scanSource, startKey, endKey);
      result.setHasMore(hasMore);
      if (hasMore && lastConsumedKey != null) {
        result.setResumeKey(ByteString.copyFrom(lastConsumedKey));
      }
    } catch (RuntimeException e) {
      throw new SQLException("Failed to execute ADB region scan", e);
    } finally {
      close(scanSource, request);
    }

    result.setCount(visibleCount);
    return result.build();
  }

  private static void close(VersionScanSource scanSource, RegionScan request)
      throws SQLException {
    try {
      scanSource.close();
    } catch (Exception e) {
      throw new SQLException("Failed to close ADB region scan, regionId="
          + request.getRegionId(), e);
    }
  }

  private static VisibleVersion consumeLogicalKey(VersionScanSource scanSource,
      byte[] logicalKey, long startTs) {
    VersionKey visibleVersion = null;
    RowValue visibleValue = null;
    byte[] lastConsumedKey = null;
    while (scanSource.isValid()) {
      VersionKey versionKey = VersionKey.fromBytes(scanSource.key());
      DataKey dataKey = versionKey.toDataKey();
      if (!KeyCodec.startsWith(dataKey.toBytes(), logicalKey)) {
        break;
      }
      RowValue value = RowValue.decodeValue(scanSource.value());
      if (visibleValue == null && versionKey.isCommited()
          && value != null && value.commitTs <= startTs) {
        visibleVersion = versionKey;
        visibleValue = value;
      }
      lastConsumedKey = scanSource.key();
      scanSource.advance();
    }
    return new VisibleVersion(visibleVersion, visibleValue, lastConsumedKey);
  }

  private static RegionVisibleRow toRegionVisibleRow(VersionKey versionKey,
      DataKey dataKey, RowValue value) {
    RegionVisibleRow.Builder row = RegionVisibleRow.newBuilder()
        .setRowId(dataKey.getRowId())
        .setKey(ByteString.copyFrom(dataKey.toBytes()))
        .setPayload(ByteString.copyFrom(value.payload));
    if (versionKey instanceof VersionIndexKey && dataKey instanceof IndexKey) {
      IndexKey indexKey = (IndexKey) dataKey;
      row.setIndexRow(true)
          .setIndexId(indexKey.getIndexId())
          .setIndex(ByteString.copyFrom(indexKey.getIndex()));
    }
    return row.build();
  }

  private static boolean nextKeyInRange(VersionScanSource scanSource,
      byte[] startKey, byte[] endKey) {
    if (!scanSource.isValid()) {
      return false;
    }
    VersionKey versionKey = VersionKey.fromBytes(scanSource.key());
    return contains(versionKey.toDataKey().toBytes(), startKey, endKey);
  }

  private static boolean contains(byte[] key, byte[] startKey, byte[] endKey) {
    return (startKey == null || KeyCodec.compare(key, startKey) >= 0)
        && (endKey == null || KeyCodec.compare(key, endKey) < 0);
  }

  private static boolean isReadable(RowValue value) {
    return value != null
        && !value.deleted
        && value.payload != null
        && value.payload.length > 0;
  }

  private static final class VisibleVersion {
    private final VersionKey versionKey;
    private final RowValue value;
    private final byte[] lastConsumedKey;

    private VisibleVersion(VersionKey versionKey, RowValue value,
        byte[] lastConsumedKey) {
      this.versionKey = versionKey;
      this.value = value;
      this.lastConsumedKey = lastConsumedKey;
    }
  }
}
