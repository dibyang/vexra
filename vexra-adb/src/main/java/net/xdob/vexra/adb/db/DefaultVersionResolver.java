package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.adb.key.*;

import java.sql.SQLException;

public final class DefaultVersionResolver implements VersionResolver {

  private final DbStore dbStore;
  private final VisibleRowResolver visibleRowResolver;

  public DefaultVersionResolver(DbStore dbStore) {
    this.dbStore = dbStore;
    this.visibleRowResolver = new DefaultVisibleRowResolver(dbStore);
  }

  public RowValue getLatestCommitted(Key key) throws SQLException {
    byte[] prefix = key.toBytes();
    byte[] end = KeyCodec.prefixEnd(prefix);

    try (VersionScanSource scan = dbStore.openVersionScanSource(ScanDirection.FORWARD)) {
      scan.seekToRangeStart(prefix, end);

      while (scan.isValid()) {
        byte[] rawKey = scan.key();
        if (rawKey == null || !KeyCodec.startsWith(rawKey, prefix)) {
          return null;
        }

        VersionKey versionKey = VersionKey.fromBytes(rawKey);
        if (!versionKey.isCommited()) {
          scan.advance();
          continue;
        }

        RowValue val = RowValue.decodeValue(scan.value());
        if (val.deleted) {
          return null;
        }

        val.rowKey = versionKey.getRowId();
        return val;
      }

      return null;
    } catch (Exception e) {
      if (e instanceof SQLException) {
        throw (SQLException) e;
      }
      throw new SQLException("Failed to get latest committed version", e);
    }
  }

  @Override
  public RowValue getVisible(Transaction2 txn, DataKey key) {
    return visibleRowResolver.getVisible(txn, key);
  }

  @Override
  public RowValue getLatestCommittedBefore(DataKey key, long startTs) throws SQLException {
    byte[] prefix = key.toBytes();
    byte[] end = KeyCodec.prefixEnd(prefix);

    try (VersionScanSource scan = dbStore.openVersionScanSource(ScanDirection.FORWARD)) {
      scan.seekToRangeStart(prefix, end);
      return resolveCommittedInCurrentLogicalRow(scan, prefix, startTs);
    } catch (Exception e) {
      if (e instanceof SQLException) {
        throw (SQLException) e;
      }
      throw new SQLException("Failed to get latest committed before", e);
    }
  }

  @Override
  public RowValue first(Transaction2 txn, PrefixKey prefixKey) throws SQLException {
    byte[] tablePrefix = prefixKey.toBytes();
    byte[] end = KeyCodec.prefixEnd(tablePrefix);

    try (VersionScanSource scan = dbStore.openVersionScanSource(ScanDirection.FORWARD)) {
      scan.seekToRangeStart(tablePrefix, end);

      while (scan.isValid()) {
        byte[] rawKey = scan.key();
        if (rawKey == null || !KeyCodec.startsWith(rawKey, tablePrefix)) {
          return null;
        }

        VersionKey versionKey = VersionKey.fromBytes(rawKey);
        DataKey dataKey = versionKey.toDataKey();
        byte[] rowPrefix = dataKey.toBytes();

        RowValue visible = resolveVisibleInCurrentLogicalRow(scan, rowPrefix, txn);
        if (visible != null) {
          visible.rowKey = dataKey.getRowId();
          return visible;
        }

        // 涓婇潰鐨?resolveVisibleInCurrentLogicalRow 杩斿洖鏃讹紝
        // scan 宸茬粡璧板埌涓嬩竴 logical row 鎴栬秺鐣屼簡锛屾墍浠ヨ繖閲屼笉鐢ㄥ啀棰濆 skip
      }

      return null;
    } catch (Exception e) {
      if (e instanceof SQLException) {
        throw (SQLException) e;
      }
      throw new SQLException("Failed to find first visible row", e);
    }
  }

  @Override
  public RowValue last(Transaction2 txn, PrefixKey prefixKey) throws SQLException {
    byte[] tablePrefix = prefixKey.toBytes();
    byte[] end = KeyCodec.prefixEnd(tablePrefix);

    try (VersionScanSource scan = dbStore.openVersionScanSource(ScanDirection.REVERSE)) {
      scan.seekToRangeStart(tablePrefix, end);

      while (scan.isValid()) {
        byte[] rawKey = scan.key();
        if (rawKey == null || !KeyCodec.startsWith(rawKey, tablePrefix)) {
          return null;
        }

        VersionKey versionKey = VersionKey.fromBytes(rawKey);
        DataKey dataKey = versionKey.toDataKey();
        byte[] rowPrefix = dataKey.toBytes();

        RowValue visible = resolveVisibleInCurrentLogicalRowReverse(scan, rowPrefix, txn, tablePrefix);
        if (visible != null) {
          visible.rowKey = dataKey.getRowId();
          return visible;
        }

        // 鍚岀悊锛宺everse 鐗堟湰鍦ㄨ繑鍥炴椂涔熷凡缁忚烦鍒板墠涓€涓?logical row 浜?
      }

      return null;
    } catch (Exception e) {
      if (e instanceof SQLException) {
        throw (SQLException) e;
      }
      throw new SQLException("Failed to find last visible row", e);
    }
  }

  /**
   * FORWARD:
   * scan 杩涘叆鏃跺簲浣嶄簬鏌愪釜 logical row 鐨勭涓€鏉＄増鏈褰曪紱
   * 杩斿洖鏃讹紝scan 宸茬Щ鍔ㄥ埌鈥滀笅涓€ logical row 鐨勭涓€鏉¤褰曗€濇垨瓒婄晫銆?
   */
  private RowValue resolveVisibleInCurrentLogicalRow(
      VersionScanSource scan,
      byte[] rowPrefix,
      Transaction2 txn
  ) {
    RowValue firstCommittedVisible = null;

    while (scan.isValid()) {
      byte[] rawKey = scan.key();
      if (rawKey == null || !KeyCodec.startsWith(rawKey, rowPrefix)) {
        break;
      }

      VersionKey vk = VersionKey.fromBytes(rawKey);
      RowValue rowValue = RowValue.decodeValue(scan.value());

      // 鑷繁鐨?intent 浼樺厛
      if (!vk.isCommited()) {
        if (vk.getTxnId() == txn.getTxnId()) {
          return rowValue.deleted ? null : rowValue;
        }
        scan.advance();
        continue;
      }

      // 绗竴鏉℃弧瓒?snapshot 鐨?committed 灏辨槸瑕佺殑
      if (rowValue.commitTs <= txn.getStartTs()) {
        firstCommittedVisible = rowValue.deleted ? null : rowValue;
        skipCurrentLogicalRowForward(scan, rowPrefix);
        return firstCommittedVisible;
      }

      scan.advance();
    }

    return null;
  }

  /**
   * REVERSE:
   * scan 杩涘叆鏃朵綅浜庢煇涓?logical row 鐨勬煇鏉＄増鏈褰曪紱
   * 杩斿洖鏃讹紝scan 宸茬Щ鍔ㄥ埌鈥滃墠涓€涓?logical row 鐨勬煇鏉¤褰曗€濇垨瓒婄晫銆?
   */
  private RowValue resolveVisibleInCurrentLogicalRowReverse(
      VersionScanSource scan,
      byte[] rowPrefix,
      Transaction2 txn,
      byte[] tablePrefix
  ) {
    while (scan.isValid()) {
      byte[] rawKey = scan.key();
      if (rawKey == null || !KeyCodec.startsWith(rawKey, rowPrefix)) {
        break;
      }

      VersionKey vk = VersionKey.fromBytes(rawKey);
      RowValue rowValue = RowValue.decodeValue(scan.value());

      if (!vk.isCommited()) {
        if (vk.getCommitTs() == txn.getTxnId()) {
          skipCurrentLogicalRowReverse(scan, rowPrefix, tablePrefix);
          return rowValue.deleted ? null : rowValue;
        }
        scan.advance();
        continue;
      }

      if (rowValue.commitTs <= txn.getStartTs()) {
        skipCurrentLogicalRowReverse(scan, rowPrefix, tablePrefix);
        return rowValue.deleted ? null : rowValue;
      }

      scan.advance();
    }

    return null;
  }

  /**
   * scan 杩涘叆鏃朵綅浜庤 row 鐨勭涓€鏉¤褰曪紱
   * 杩斿洖鏃朵綅浜庝笅涓€ row 鐨勭涓€鏉¤褰曟垨瓒婄晫銆?
   */
  private RowValue resolveCommittedInCurrentLogicalRow(
      VersionScanSource scan,
      byte[] rowPrefix,
      long startTs
  ) {
    while (scan.isValid()) {
      byte[] rawKey = scan.key();
      if (rawKey == null || !KeyCodec.startsWith(rawKey, rowPrefix)) {
        return null;
      }

      VersionKey vk = VersionKey.fromBytes(rawKey);
      if (!vk.isCommited()) {
        scan.advance();
        continue;
      }

      RowValue rowValue = RowValue.decodeValue(scan.value());
      if (rowValue.commitTs <= startTs) {
        return rowValue.deleted ? null : rowValue;
      }

      scan.advance();
    }
    return null;
  }

  private void skipCurrentLogicalRowForward(VersionScanSource scan, byte[] rowPrefix) {
    while (scan.isValid()) {
      scan.advance();
      if (!scan.isValid()) {
        return;
      }
      byte[] rawKey = scan.key();
      if (rawKey == null || !KeyCodec.startsWith(rawKey, rowPrefix)) {
        return;
      }
    }
  }

  private void skipCurrentLogicalRowReverse(
      VersionScanSource scan,
      byte[] rowPrefix,
      byte[] tablePrefix
  ) {
    while (scan.isValid()) {
      scan.advance();
      if (!scan.isValid()) {
        return;
      }
      byte[] rawKey = scan.key();
      if (rawKey == null) {
        return;
      }
      if (!KeyCodec.startsWith(rawKey, tablePrefix)) {
        return;
      }
      if (!KeyCodec.startsWith(rawKey, rowPrefix)) {
        return;
      }
    }
  }
}