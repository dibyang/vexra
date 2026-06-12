package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.adb.key.DataKey;
import net.xdob.vexra.adb.key.IndexKey;
import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.VersionIndexKey;
import net.xdob.vexra.adb.key.VersionKey;
import net.xdob.vexra.cluster.region.KeyRange;
import net.xdob.vexra.cluster.sql.RegionQueryResult;
import net.xdob.vexra.cluster.sql.RegionScanTask;
import org.h2.value.Value;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ADB 本地 region scan task 执行器。
 *
 * <p>该执行器是分布式读路径的本地 adapter：它把 {@link RegionScanTask}
 * 转换成 ADB version key 范围扫描，并复用现有 MVCC 可见性 resolver。它不负责远程
 * RPC、调度、重试或 SQL planner 改造，后续远程 executor 可以以该类的输出契约作为
 * 兼容基线。</p>
 */
public final class AdbLocalRegionScanExecutor {
  private final DbStore store;
  private final VisibleRowResolver rowResolver;
  private final VisibleIndexResolver indexResolver;

  /**
   * 创建 ADB 本地 region scan task 执行器。
   *
   * @param store ADB 底层 store
   */
  public AdbLocalRegionScanExecutor(DbStore store) {
    this.store = Objects.requireNonNull(store, "store == null");
    this.rowResolver = new DefaultVisibleRowResolver(store);
    this.indexResolver = new DefaultVisibleIndexResolver(store);
  }

  /**
   * 执行普通 region scan task，返回行集合。
   *
   * @param txn 当前事务
   * @param task region scan task
   * @return region 查询结果
   * @throws SQLException 当底层扫描或可见性解析失败时抛出
   */
  public RegionQueryResult execute(Transaction2 txn, RegionScanTask task)
      throws SQLException {
    return executeInternal(txn, task, false);
  }

  /**
   * 执行 count-only region scan task。
   *
   * @param txn 当前事务
   * @param task region scan task
   * @return 仅包含 count 的 region 查询结果
   * @throws SQLException 当底层扫描或可见性解析失败时抛出
   */
  public RegionQueryResult executeCount(Transaction2 txn, RegionScanTask task)
      throws SQLException {
    return executeInternal(txn, task, true);
  }

  private RegionQueryResult executeInternal(Transaction2 txn,
      RegionScanTask task, boolean countOnly) throws SQLException {
    Objects.requireNonNull(txn, "txn == null");
    Objects.requireNonNull(task, "task == null");

    List<Map<String, Object>> rows = new ArrayList<>();
    long count = 0;
    int limit = task.getLimit();

    VersionScanSource scan = store.openVersionScanSource(ScanDirection.FORWARD);
    try {
      KeyRange range = task.getKeyRange();
      scan.seekToRangeStart(range.getStartKey(), range.getEndKey());

      while (scan.isValid()) {
        VersionKey versionKey = VersionKey.fromBytes(scan.key());
        DataKey dataKey = versionKey.toDataKey();
        byte[] logicalPrefix = dataKey.toBytes();

        if (!range.contains(logicalPrefix)) {
          break;
        }

        RowValue visible = resolveVisible(txn, versionKey, dataKey);
        skipLogicalGroup(scan, logicalPrefix, range);

        if (!isReadable(visible)) {
          continue;
        }

        count++;
        if (!countOnly) {
          rows.add(toRow(versionKey, dataKey, visible));
          if (limit > 0 && rows.size() >= limit) {
            break;
          }
        } else if (limit > 0 && count >= limit) {
          break;
        }
      }
    } catch (RuntimeException e) {
      throw new SQLException("Failed to execute local ADB region scan, regionId="
          + task.getRegionId(), e);
    } finally {
      close(scan, task);
    }

    return new RegionQueryResult(task.getRegionId(), rows, countOnly ? count : 0);
  }

  private static void close(VersionScanSource scan, RegionScanTask task)
      throws SQLException {
    try {
      scan.close();
    } catch (Exception e) {
      throw new SQLException("Failed to close local ADB region scan, regionId="
          + task.getRegionId(), e);
    }
  }

  private RowValue resolveVisible(Transaction2 txn, VersionKey versionKey,
      DataKey dataKey) {
    if (dataKey.isIndex()) {
      RowValue visibleIndex = indexResolver.getVisibleIndex(txn,
          dataKey.toBytes());
      if (!isReadable(visibleIndex)) {
        return null;
      }
      RowKey rowKey = RowKey.of(dataKey.getTabID(), dataKey.getRowId());
      return rowResolver.getVisible(txn, rowKey);
    }
    return rowResolver.getVisible(txn, dataKey);
  }

  private static void skipLogicalGroup(VersionScanSource scan,
      byte[] logicalPrefix, KeyRange range) {
    while (scan.isValid()) {
      scan.advance();
      if (!scan.isValid()) {
        return;
      }
      VersionKey next = VersionKey.fromBytes(scan.key());
      byte[] nextLogical = next.toDataKey().toBytes();
      if (!range.contains(nextLogical)) {
        return;
      }
      if (!startsWith(nextLogical, logicalPrefix)) {
        return;
      }
    }
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

  private static boolean startsWith(byte[] key, byte[] prefix) {
    if (key == null || prefix == null || key.length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if (key[i] != prefix[i]) {
        return false;
      }
    }
    return true;
  }

  private static String toHex(byte[] bytes) {
    StringBuilder builder = new StringBuilder();
    for (byte b : bytes) {
      builder.append(String.format("%02x", b & 0xff));
    }
    return builder.toString();
  }
}
