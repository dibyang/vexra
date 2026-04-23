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

        // 上面的 resolveVisibleInCurrentLogicalRow 返回时，
        // scan 已经走到下一 logical row 或越界了，所以这里不用再额外 skip
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

        // 同理，reverse 版本在返回时也已经跳到前一个 logical row 了
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
   * scan 进入时应位于某个 logical row 的第一条版本记录；
   * 返回时，scan 已移动到“下一 logical row 的第一条记录”或越界。
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

      // 自己的 intent 优先
      if (!vk.isCommited()) {
        if (vk.getTxnId() == txn.getTxnId()) {
          return rowValue.deleted ? null : rowValue;
        }
        scan.advance();
        continue;
      }

      // 第一条满足 snapshot 的 committed 就是要的
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
   * scan 进入时位于某个 logical row 的某条版本记录；
   * 返回时，scan 已移动到“前一个 logical row 的某条记录”或越界。
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
   * scan 进入时位于该 row 的第一条记录；
   * 返回时位于下一 row 的第一条记录或越界。
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