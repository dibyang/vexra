package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.adb.ldb.LdbStore;
import org.h2.value.ValueVarchar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ADB durable lock scanner 测试。
 *
 * <p>验证 scanner 能从 TXN CF 中识别 `TxnKeyType.LOCK` 记录，跳过事务引用等
 * 其他 TXN 记录，并按 TTL 筛选过期 lock。</p>
 */
class AdbTxnLockScannerTest {
  @TempDir
  private Path tempDir;

  /**
   * 验证 scanner 只返回 durable lock record，不把 WRITE_REF 误识别为 lock。
   */
  @Test
  void shouldScanDurableLocksOnly() throws Exception {
    try (LdbStore store = new LdbStore(tempDir.resolve("scan").toString())) {
      RowKey key = rowKey(1);
      prewriteWithLock(store, 10, key, 1, 5);

      List<AdbTxnLock> locks = new AdbTxnLockScanner(store).scanLocks(0);

      assertEquals(1, locks.size());
      assertEquals(10, locks.get(0).getTxnId());
      assertEquals("r1", locks.get(0).getRegionId());
    }
  }

  /**
   * 验证 scanner 可以按 TTL 返回过期 lock。
   */
  @Test
  void shouldScanExpiredLocksByTtl() throws Exception {
    try (LdbStore store = new LdbStore(
        tempDir.resolve("expired-scan").toString())) {
      prewriteWithLock(store, 11, rowKey(2), 1, 5);
      prewriteWithLock(store, 12, rowKey(3), 10, 20);

      List<AdbTxnLock> locks = new AdbTxnLockScanner(store)
          .scanExpiredLocks(7, 0);

      assertEquals(1, locks.size());
      assertEquals(11, locks.get(0).getTxnId());
    }
  }

  static void prewriteWithLock(LdbStore store, long txnId, RowKey key,
      long startTs, long ttlMillis) throws Exception {
    AdbPrewriteApplicator.prewrite(store, txnId, startTs,
        Collections.singletonList(new AdbRegionMutation(key,
            rowValue(txnId, "lock-scanner"))),
        Collections.singletonList(new AdbTxnLock(txnId, key.toBytes(),
            key.toBytes(), startTs, "r1", ttlMillis)));
  }

  private static RowValue rowValue(long txnId, String value) {
    RowValue rowValue = new RowValue();
    rowValue.txnId = txnId;
    rowValue.payload = RowCodec.encode(ValueVarchar.get(value));
    return rowValue;
  }

  private static RowKey rowKey(long rowId) {
    return RowKey.of(TabId.of(1, 0L), rowId);
  }
}
