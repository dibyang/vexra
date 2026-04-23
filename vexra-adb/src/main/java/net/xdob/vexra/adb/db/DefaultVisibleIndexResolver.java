package net.xdob.vexra.adb.db;


import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.adb.key.VersionIndexKey;

import java.util.Objects;

/**
 * 基于 RocksDB 的索引可见版本解析器。
 *
 * 语义：
 * 1. 给定一个逻辑索引项前缀 logicalPrefix，扫描该逻辑索引项的所有物理版本
 * 2. 返回当前事务可见的最新版本
 * 3. 若没有可见版本，则返回 null
 *
 * 可见性规则：
 * - 已提交版本可见
 * - 当前事务自己写入的未提交版本可见
 * - 其他事务未提交版本不可见，继续向后扫描
 *
 * 前提：
 * - 同一逻辑索引项的所有物理版本 key 都以 logicalPrefix 为前缀
 * - 版本排列顺序保证 seek(logicalPrefix) 后先遇到的是“更新的版本”
 */
public final class DefaultVisibleIndexResolver implements VisibleIndexResolver {

  private final DbStore store;

  public DefaultVisibleIndexResolver(DbStore store) {
    this.store = store;
  }

  @Override
  public RowValue getVisibleIndex(Transaction2 txn, byte[] logicalPrefix) {
    Objects.requireNonNull(txn, "txn");
    Objects.requireNonNull(logicalPrefix, "logicalPrefix");

    byte[] end = KeyCodec.prefixEnd(logicalPrefix);
    try (VersionScanSource scan = store.openVersionScanSource(ScanDirection.FORWARD)) {
      scan.seekToRangeStart(logicalPrefix, end);

      while (scan.isValid() && KeyCodec.startsWith(scan.key(), logicalPrefix)) {
        VersionIndexKey versionIndexKey = (VersionIndexKey) VersionIndexKey.fromBytes(scan.key());
        RowValue rowValue = RowValue.decodeValue(scan.value());

        if (isVisibleTo(txn, versionIndexKey, rowValue)) {
          return rowValue;
        }

        scan.advance();
      }

      return null;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private boolean isVisibleTo(Transaction2 txn, VersionIndexKey versionIndexKey, RowValue rowValue) {
    if (versionIndexKey.isCommited()) {
      return true;
    }
    return rowValue.txnId == txn.getTxnId();
  }
}
