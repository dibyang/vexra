package net.xdob.vexra.adb.db;


import net.xdob.vexra.adb.key.*;

import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * 鍩轰簬鎶借薄鎵弿婧愮殑绱㈠紩鎵弿娓告爣銆?
 *
 * 璇箟锛?
 * 1. 搴曞眰鎵弿鐗╃悊绱㈠紩鐗堟湰 key
 * 2. 瀵瑰悓涓€閫昏緫绱㈠紩椤癸紝鍙繑鍥炲綋鍓嶄簨鍔″彲瑙佺殑鏈€鏂扮増鏈?
 * 3. 鑻ョ储寮曢」鍙浣嗗搴斾富琛ㄨ涓嶅彲瑙?宸插垹闄わ紝鍒欒烦杩?
 * 4. 涓嶇洿鎺ヤ緷璧?RocksIterator
 */
public final class IndexScanCursor implements AutoCloseable {

  private final Transaction2 txn;
  private final VersionScanSource scanSource;
  private final VisibleIndexResolver visibleIndexResolver;
  private final VisibleRowResolver visibleRowResolver;
  private final byte[] startKey;
  private final byte[] endKey;

  private IndexKey current;
  private boolean closed = false;

  public IndexScanCursor(
      Transaction2 txn,
      VersionScanSource scanSource,
      VisibleIndexResolver visibleIndexResolver,
      VisibleRowResolver visibleRowResolver,
      PrefixKey prefixKey,
      TableKey min,
      TableKey max
  ) {
    this.txn = Objects.requireNonNull(txn, "txn");
    this.scanSource = Objects.requireNonNull(scanSource, "scanSource");
    this.visibleIndexResolver = Objects.requireNonNull(visibleIndexResolver, "visibleIndexResolver");
    this.visibleRowResolver = Objects.requireNonNull(visibleRowResolver, "visibleRowResolver");

    this.startKey = min != null ? min.toBytes() : prefixKey.toBytes();
    this.endKey = max != null ? max.toBytes() : KeyCodec.prefixEnd(prefixKey.toBytes());

    init();
  }

  private void init() {
    scanSource.seekToRangeStart(startKey, endKey);
  }

  public boolean next() {
    ensureOpen();
    current = null;

    while (scanSource.isValid()) {
      byte[] raw = scanSource.key();

      if (!withinRange(raw)) {
        close();
        return false;
      }

      VersionIndexKey first = (VersionIndexKey) VersionIndexKey.fromBytes(raw);
      byte[] logicalPrefix = first.toDataKey().toBytes();

      RowValue visibleIndex = visibleIndexResolver.getVisibleIndex(txn, logicalPrefix);

      // 鏃犺绱㈠紩鐗堟湰鏄惁鍙锛岄兘鍏堣烦杩囧綋鍓嶉€昏緫绱㈠紩缁?
      skipLogicalGroup(logicalPrefix);

      if (visibleIndex == null || visibleIndex.deleted) {
        continue;
      }

      RowKey rowKey = RowKey.of(first.getTabID(), first.getRowId());
      RowValue row = visibleRowResolver.getVisible(txn, rowKey);
      if (row == null || row.deleted || row.payload == null || row.payload.length == 0) {
        continue;
      }

      current = (IndexKey) first.toDataKey();
      return true;
    }

    close();
    return false;
  }

  public IndexKey get() {
    ensureOpen();
    if (current == null) {
      throw new IllegalStateException("Cursor is not positioned on a valid index row");
    }
    return current;
  }

  public IndexKey nextKey() {
    if (!next()) {
      throw new NoSuchElementException();
    }
    return get();
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

  /**
   * 璺宠繃褰撳墠閫昏緫绱㈠紩椤圭殑鎵€鏈夌墿鐞嗙増鏈?
   */
  private void skipLogicalGroup(byte[] logicalPrefix) {
    while (scanSource.isValid() && startsWith(scanSource.key(), logicalPrefix)) {
      scanSource.advance();
    }
  }

  private boolean withinRange(byte[] key) {
    if (compare(key, startKey) < 0) {
      return false;
    }
    return endKey == null || compare(key, endKey) < 0;
  }

  public static boolean startsWith(byte[] a, byte[] prefix) {
    if (a == null || prefix == null) {
      return false;
    }
    if (a.length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if (a[i] != prefix[i]) {
        return false;
      }
    }
    return true;
  }

  public static int compare(byte[] a, byte[] b) {
    int n = Math.min(a.length, b.length);
    for (int i = 0; i < n; i++) {
      int diff = (a[i] & 0xff) - (b[i] & 0xff);
      if (diff != 0) {
        return diff;
      }
    }
    return a.length - b.length;
  }
}
