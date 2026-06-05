package net.xdob.vexra.adb.db;


import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.adb.key.VersionIndexKey;

import java.util.Objects;

/**
 * 鍩轰簬 RocksDB 鐨勭储寮曞彲瑙佺増鏈В鏋愬櫒銆?
 *
 * 璇箟锛?
 * 1. 缁欏畾涓€涓€昏緫绱㈠紩椤瑰墠缂€ logicalPrefix锛屾壂鎻忚閫昏緫绱㈠紩椤圭殑鎵€鏈夌墿鐞嗙増鏈?
 * 2. 杩斿洖褰撳墠浜嬪姟鍙鐨勬渶鏂扮増鏈?
 * 3. 鑻ユ病鏈夊彲瑙佺増鏈紝鍒欒繑鍥?null
 *
 * 鍙鎬ц鍒欙細
 * - 宸叉彁浜ょ増鏈彲瑙?
 * - 褰撳墠浜嬪姟鑷繁鍐欏叆鐨勬湭鎻愪氦鐗堟湰鍙
 * - 鍏朵粬浜嬪姟鏈彁浜ょ増鏈笉鍙锛岀户缁悜鍚庢壂鎻?
 *
 * 鍓嶆彁锛?
 * - 鍚屼竴閫昏緫绱㈠紩椤圭殑鎵€鏈夌墿鐞嗙増鏈?key 閮戒互 logicalPrefix 涓哄墠缂€
 * - 鐗堟湰鎺掑垪椤哄簭淇濊瘉 seek(logicalPrefix) 鍚庡厛閬囧埌鐨勬槸鈥滄洿鏂扮殑鐗堟湰鈥?
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
