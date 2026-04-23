package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.adb.key.DataKey;
import net.xdob.vexra.adb.key.VersionKey;

import java.util.Objects;

/**
 * 可见版本解析器。
 *
 * 语义：
 * 1. 对指定逻辑行(rowKey)扫描其所有物理版本
 * 2. 返回当前事务可见的最新版本
 * 3. 若该逻辑行当前对事务不可见，或最新可见版本为删除标记，则返回 null
 *
 * 约定：
 * - 同一逻辑行的所有版本 key 必须以 rowKey.toBytes() 为前缀
 * - 版本顺序必须保证“先扫到的就是更新的版本”
 */


public final class DefaultVisibleRowResolver implements VisibleRowResolver {

  private final DbStore store;

  public DefaultVisibleRowResolver(DbStore store) {
    this.store = store;
  }

  @Override
  public RowValue getVisible(Transaction2 txn, DataKey rowKey) {
    Objects.requireNonNull(txn, "txn");
    Objects.requireNonNull(rowKey, "rowKey");

    // 1. 先看当前事务本地 writeSet（内存 intent）
    RowValue local = txn.getLocalWrite(rowKey);
    if (local != null) {
      return local.deleted ? null : copyWithRowKey(local, rowKey.getRowId());
    }

    // 2. 再扫 store 中的 committed versions
    byte[] prefix = rowKey.toBytes();
    byte[] end = KeyCodec.prefixEnd(prefix);

    try (VersionScanSource scan = store.openVersionScanSource(ScanDirection.FORWARD)) {
      scan.seekToRangeStart(prefix, end);

      while (scan.isValid() && KeyCodec.startsWith(scan.key(), prefix)) {
        VersionKey versionKey = VersionKey.fromBytes(scan.key());

        // store 里现在只关心 committed；非 committed 直接跳过
        if (!versionKey.isCommited()) {
          scan.advance();
          continue;
        }

        RowValue rowValue = RowValue.decodeValue(scan.value());

        // 只允许看到 startTs 之前已经提交的版本
        if (rowValue.commitTs <= txn.getStartTs()) {
          if (rowValue.deleted) {
            return null;
          }
          rowValue.rowKey = versionKey.getRowId();
          return rowValue;
        }

        scan.advance();
      }

      return null;
    } catch (Exception e) {
      throw new RuntimeException("Failed to resolve visible row for " + rowKey, e);
    }
  }

  private RowValue copyWithRowKey(RowValue src, long rowId) {
    RowValue copy = new RowValue();
    copy.deleted = src.deleted;
    copy.payload = src.payload;
    copy.txnId = src.txnId;
    copy.commitTs = src.commitTs;
    copy.rowKey = rowId;
    return copy;
  }
}
