package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.DataKey;
import net.xdob.vexra.adb.key.PrefixKey;
import net.xdob.vexra.adb.key.VersionKey;

public final class TableScanCursor implements AutoCloseable {

  private final Transaction2 txn;
  private final VersionScanSource scanSource;
  private final VisibleRowResolver visibleResolver;
  private final byte[] tablePrefix;
  private final Long minRowId;
  private final Long maxRowId;

  private RowValue current;
  private boolean closed = false;

  public TableScanCursor(
      Transaction2 txn,
      VersionScanSource scanSource,
      VisibleRowResolver visibleResolver,
      PrefixKey prefixKey,
      Long minRowId,
      Long maxRowId
  ) {
    this.txn = txn;
    this.scanSource = scanSource;
    this.visibleResolver = visibleResolver;
    this.tablePrefix = prefixKey.toBytes();
    this.minRowId = minRowId;
    this.maxRowId = maxRowId;

    init(prefixKey);
  }

  private void init(PrefixKey prefixKey) {
    byte[] lowerInclusive =
        minRowId != null ? buildRowSeekKey(prefixKey, minRowId) : tablePrefix;

    byte[] upperExclusive =
        maxRowId != null
            ? KeyCodec.prefixEnd(buildRowSeekKey(prefixKey, maxRowId))
            : KeyCodec.prefixEnd(tablePrefix);

    scanSource.seekToRangeStart(lowerInclusive, upperExclusive);
  }

  public boolean next() {
    ensureOpen();
    current = null;

    while (scanSource.isValid() && startsWith(scanSource.key(), tablePrefix)) {
      VersionKey versionKey = VersionKey.fromBytes(scanSource.key());
      DataKey dataKey = versionKey.toDataKey();
      byte[] rowPrefix = dataKey.toBytes();
      long rowId = dataKey.getRowId();

      if (!inRowIdRange(rowId)) {
        if (scanSource.direction() == ScanDirection.FORWARD
            && maxRowId != null && rowId > maxRowId) {
          close();
          return false;
        }
        if (scanSource.direction() == ScanDirection.REVERSE
            && minRowId != null && rowId < minRowId) {
          close();
          return false;
        }

        skipCurrentLogicalRow(rowPrefix);
        continue;
      }

      RowValue visible = resolveVisibleInCurrentLogicalRow(dataKey,
          rowPrefix);

      // 鏃犺鍙涓嶅彲瑙侊紝閮借烦杩囧綋鍓嶉€昏緫琛屾墍鏈夌増鏈?
      // 鍙繑鍥炵湡姝ｅ彲璇汇€佸彲瑙ｇ爜鐨勮
      if (visible == null) {
        continue;
      }
      if (visible.deleted) {
        continue;
      }
      if (visible.payload == null || visible.payload.length == 0) {
        continue;
      }

      current = visible;
      return true;
    }

    close();
    return false;
  }

  private RowValue resolveVisibleInCurrentLogicalRow(DataKey dataKey,
      byte[] rowPrefix) {
    RowValue local = txn.getLocalWrite(dataKey);
    if (local != null) {
      skipCurrentLogicalRow(rowPrefix);
      return local.deleted ? null : copyWithRowKey(local, dataKey.getRowId());
    }

    while (scanSource.isValid()) {
      byte[] rawKey = scanSource.key();
      if (rawKey == null || !startsWith(rawKey, rowPrefix)) {
        return null;
      }
      VersionKey versionKey = VersionKey.fromBytes(rawKey);
      if (!versionKey.isCommited()) {
        scanSource.advance();
        continue;
      }
      RowValue rowValue = RowValue.decodeValue(scanSource.value());
      if (rowValue.commitTs <= txn.getStartTs()) {
        skipCurrentLogicalRow(rowPrefix);
        if (rowValue.deleted) {
          return null;
        }
        rowValue.rowKey = dataKey.getRowId();
        return rowValue;
      }
      scanSource.advance();
    }
    return null;
  }

  private static RowValue copyWithRowKey(RowValue src, long rowId) {
    RowValue copy = new RowValue();
    copy.deleted = src.deleted;
    copy.payload = src.payload;
    copy.txnId = src.txnId;
    copy.commitTs = src.commitTs;
    copy.rowKey = rowId;
    return copy;
  }

  public RowValue get() {
    ensureOpen();
    if (current == null) {
      throw new IllegalStateException("Cursor is not positioned on a valid row");
    }
    return current;
  }

  private boolean inRowIdRange(long rowId) {
    if (minRowId != null && rowId < minRowId) {
      return false;
    }
    if (maxRowId != null && rowId > maxRowId) {
      return false;
    }
    return true;
  }

  private void skipCurrentLogicalRow(byte[] currentRowPrefix) {
    while (scanSource.isValid()) {
      scanSource.advance();
      if (!scanSource.isValid()) {
        return;
      }

      byte[] key = scanSource.key();
      if (!startsWith(key, tablePrefix)) {
        return;
      }
      if (!startsWith(key, currentRowPrefix)) {
        return;
      }
    }
  }

  @Override
  public void close() {
    if (!closed) {
      closed = true;
      try {
        scanSource.close();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  private void ensureOpen() {
    if (closed) {
      throw new IllegalStateException("Cursor already closed");
    }
  }

  private static byte[] buildRowSeekKey(PrefixKey prefixKey, long rowId) {
    DynamicByteBuffer b = DynamicByteBuffer.c();
    b.put(prefixKey.toBytes());
    b.putLong(rowId);
    return b.toArray();
  }

  public static boolean startsWith(byte[] key, byte[] prefix) {
    if (key == null || prefix == null) {
      return false;
    }
    if (key.length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if (key[i] != prefix[i]) {
        return false;
      }
    }
    return true;
  }
}
