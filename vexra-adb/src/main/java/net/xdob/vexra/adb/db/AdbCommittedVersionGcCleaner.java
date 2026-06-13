package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.adb.key.DataKey;
import net.xdob.vexra.adb.key.VersionKey;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * ADB committed version GC cleaner。
 *
 * <p>该 cleaner 只处理 DEFAULT CF 中已经提交的历史版本。它按 logical key 分组，
 * 保留每个 logical key 扫描到的第一个 committed version，也就是当前 key 的最新提交版本；
 * 后续早于 safe point 的旧版本才允许删除。</p>
 */
public final class AdbCommittedVersionGcCleaner {
  private static final byte[] FIRST_KEY = new byte[0];

  private final DbStore store;
  private final AdbGcSafePointManager safePointManager;

  /**
   * 创建 committed version GC cleaner。
   *
   * @param store ADB store
   * @param safePointManager GC safe point manager
   */
  public AdbCommittedVersionGcCleaner(DbStore store,
      AdbGcSafePointManager safePointManager) {
    this.store = Objects.requireNonNull(store, "store == null");
    this.safePointManager = Objects.requireNonNull(safePointManager,
        "safePointManager == null");
  }

  /**
   * 执行一轮历史 committed version 清理。
   *
   * @param limit 最多删除多少个历史版本，0 表示不限制
   * @return 清理结果
   * @throws SQLException 扫描或删除失败时抛出
   */
  public AdbGcCleanResult cleanOnce(int limit) throws SQLException {
    if (limit < 0) {
      throw new IllegalArgumentException("limit is negative: " + limit);
    }
    ScanResult scanResult = collectDeleteKeys(limit);
    if (!scanResult.deleteKeys.isEmpty()) {
      store.writeBatch(batch -> {
        for (byte[] key : scanResult.deleteKeys) {
          batch.delete(CF.DEFAULT.getCfId(), key);
        }
      });
    }
    return new AdbGcCleanResult(scanResult.scannedVersions,
        scanResult.deleteKeys.size());
  }

  private ScanResult collectDeleteKeys(int limit) throws SQLException {
    long safePoint = safePointManager.getSafePoint();
    List<byte[]> deleteKeys = new ArrayList<>();
    int scannedVersions = 0;
    byte[] currentLogicalKey = null;
    boolean preservedLatestForCurrentKey = false;

    try (VersionScanSource scan = store.openVersionScanSource(
        CF.DEFAULT.getCfId(), ScanDirection.FORWARD)) {
      scan.seekToRangeStart(FIRST_KEY, null);
      while (scan.isValid() && (limit == 0 || deleteKeys.size() < limit)) {
        VersionKey versionKey = VersionKey.fromBytes(scan.key());
        if (!versionKey.isCommited()) {
          scan.advance();
          continue;
        }
        scannedVersions++;
        DataKey logicalKey = versionKey.toDataKey();
        byte[] logicalBytes = logicalKey.toBytes();
        if (!Arrays.equals(currentLogicalKey, logicalBytes)) {
          currentLogicalKey = logicalBytes;
          preservedLatestForCurrentKey = false;
        }
        if (!preservedLatestForCurrentKey) {
          preservedLatestForCurrentKey = true;
          scan.advance();
          continue;
        }
        RowValue rowValue = RowValue.decodeValue(scan.value());
        if (rowValue != null && safePointManager.canCollect(
            rowValue.commitTs)) {
          deleteKeys.add(scan.key());
        }
        scan.advance();
      }
      return new ScanResult(scannedVersions, deleteKeys);
    } catch (SQLException e) {
      throw e;
    } catch (Exception e) {
      throw new SQLException("Failed to collect ADB committed versions", e);
    }
  }

  private static final class ScanResult {
    private final int scannedVersions;
    private final List<byte[]> deleteKeys;

    private ScanResult(int scannedVersions, List<byte[]> deleteKeys) {
      this.scannedVersions = scannedVersions;
      this.deleteKeys = deleteKeys;
    }
  }
}
