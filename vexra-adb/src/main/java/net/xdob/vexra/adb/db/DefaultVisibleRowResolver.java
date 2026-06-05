package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.adb.key.DataKey;
import net.xdob.vexra.adb.key.VersionKey;

import java.util.Objects;

/**
 * 鍙鐗堟湰瑙ｆ瀽鍣ㄣ€?
 *
 * 璇箟锛?
 * 1. 瀵规寚瀹氶€昏緫琛?rowKey)鎵弿鍏舵墍鏈夌墿鐞嗙増鏈?
 * 2. 杩斿洖褰撳墠浜嬪姟鍙鐨勬渶鏂扮増鏈?
 * 3. 鑻ヨ閫昏緫琛屽綋鍓嶅浜嬪姟涓嶅彲瑙侊紝鎴栨渶鏂板彲瑙佺増鏈负鍒犻櫎鏍囪锛屽垯杩斿洖 null
 *
 * 绾﹀畾锛?
 * - 鍚屼竴閫昏緫琛岀殑鎵€鏈夌増鏈?key 蹇呴』浠?rowKey.toBytes() 涓哄墠缂€
 * - 鐗堟湰椤哄簭蹇呴』淇濊瘉鈥滃厛鎵埌鐨勫氨鏄洿鏂扮殑鐗堟湰鈥?
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

    // 1. 鍏堢湅褰撳墠浜嬪姟鏈湴 writeSet锛堝唴瀛?intent锛?
    RowValue local = txn.getLocalWrite(rowKey);
    if (local != null) {
      return local.deleted ? null : copyWithRowKey(local, rowKey.getRowId());
    }

    // 2. 鍐嶆壂 store 涓殑 committed versions
    byte[] prefix = rowKey.toBytes();
    byte[] end = KeyCodec.prefixEnd(prefix);

    try (VersionScanSource scan = store.openVersionScanSource(ScanDirection.FORWARD)) {
      scan.seekToRangeStart(prefix, end);

      while (scan.isValid() && KeyCodec.startsWith(scan.key(), prefix)) {
        VersionKey versionKey = VersionKey.fromBytes(scan.key());

        // store 閲岀幇鍦ㄥ彧鍏冲績 committed锛涢潪 committed 鐩存帴璺宠繃
        if (!versionKey.isCommited()) {
          scan.advance();
          continue;
        }

        RowValue rowValue = RowValue.decodeValue(scan.value());

        // 鍙厑璁哥湅鍒?startTs 涔嬪墠宸茬粡鎻愪氦鐨勭増鏈?
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
