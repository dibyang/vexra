package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.IndexKey;
import net.xdob.vexra.adb.key.IndexPrefix;
import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.RowPrefix;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.adb.ldb.LdbStore;
import net.xdob.vexra.cluster.region.KeyRange;
import net.xdob.vexra.cluster.sql.RegionQueryResult;
import net.xdob.vexra.cluster.sql.RegionScanTask;
import org.h2.value.ValueVarchar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADB 本地 region scan task 执行器测试。
 *
 * <p>测试使用真实 LdbStore 和 TxnManager，覆盖 region task 到 ADB 本地 row scan、
 * index scan 和 count-only scan 的最小闭环。</p>
 */
class AdbLocalRegionScanExecutorTest {
  @TempDir
  File tempDir;

  /**
   * 验证 row range task 可以扫描可见主表行并应用 limit。
   */
  @Test
  void shouldExecuteRowRangeTaskLocally() throws Exception {
    try (LdbStore store = new LdbStore(new File(tempDir, "row-store")
        .getAbsolutePath())) {
      TxnManager manager = new TxnManager(store);
      seedRows(manager);
      AdbLocalRegionScanExecutor executor = new AdbLocalRegionScanExecutor(store);

      RegionQueryResult result = executor.execute(manager.beginTransaction(),
          rowTask(2));

      assertEquals("r-row", result.getRegionId());
      assertEquals(2, result.getRows().size());
      assertEquals("row-1", result.getRows().get(0).get("payload"));
      assertEquals("row-2", result.getRows().get(1).get("payload"));
      assertEquals(1L, result.getRows().get(0).get("row_id"));
      assertTrue(result.getRows().get(0).containsKey("key_hex"));
    }
  }

  /**
   * 验证 count-only task 只返回可见行数。
   */
  @Test
  void shouldExecuteCountTaskLocally() throws Exception {
    try (LdbStore store = new LdbStore(new File(tempDir, "count-store")
        .getAbsolutePath())) {
      TxnManager manager = new TxnManager(store);
      seedRows(manager);
      AdbLocalRegionScanExecutor executor = new AdbLocalRegionScanExecutor(store);

      RegionQueryResult result = executor.executeCount(manager.beginTransaction(),
          rowTask(0));

      assertEquals(3, result.getCount());
      assertTrue(result.getRows().isEmpty());
    }
  }

  /**
   * 验证 index range task 可以先判断索引项可见，再回查主表行。
   */
  @Test
  void shouldExecuteIndexRangeTaskLocally() throws Exception {
    try (LdbStore store = new LdbStore(new File(tempDir, "index-store")
        .getAbsolutePath())) {
      TxnManager manager = new TxnManager(store);
      seedRows(manager);
      seedIndex(manager);
      AdbLocalRegionScanExecutor executor = new AdbLocalRegionScanExecutor(store);

      RegionQueryResult result = executor.execute(manager.beginTransaction(),
          indexTask());

      assertEquals(2, result.getRows().size());
      assertEquals("row-1", result.getRows().get(0).get("payload"));
      assertEquals("row-3", result.getRows().get(1).get("payload"));
      assertEquals(2, result.getRows().get(0).get("index_id"));
      assertEquals(toHex(bytes("a")), result.getRows().get(0).get("index_hex"));
    }
  }

  /**
   * 验证 task key range 不覆盖任何数据时返回空结果。
   */
  @Test
  void shouldReturnEmptyResultForUnmatchedRange() throws Exception {
    try (LdbStore store = new LdbStore(new File(tempDir, "empty-store")
        .getAbsolutePath())) {
      TxnManager manager = new TxnManager(store);
      seedRows(manager);
      AdbLocalRegionScanExecutor executor = new AdbLocalRegionScanExecutor(store);

      RegionQueryResult result = executor.execute(manager.beginTransaction(),
          new RegionScanTask("r-empty",
              new KeyRange(bytes("z"), bytes("zz")), Collections.emptyList(),
              Collections.emptyList(), 0, 0));

      assertTrue(result.getRows().isEmpty());
      assertEquals(0, result.getCount());
    }
  }

  private static void seedRows(TxnManager manager) throws SQLException {
    Transaction2 txn = manager.beginTransaction();
    manager.put(txn, RowKey.of(tabId(), 1), rowValue("row-1"));
    manager.put(txn, RowKey.of(tabId(), 2), rowValue("row-2"));
    manager.put(txn, RowKey.of(tabId(), 3), rowValue("row-3"));
    manager.commit(txn);
  }

  private static void seedIndex(TxnManager manager) throws SQLException {
    Transaction2 txn = manager.beginTransaction();
    manager.addIndexBatch(txn, IndexPrefix.of(tabId(), 2), Arrays.asList(
        IndexKey.of(tabId(), 2, bytes("a"), 1),
        IndexKey.of(tabId(), 2, bytes("c"), 3)));
  }

  private static RowValue rowValue(String value) {
    RowValue rowValue = new RowValue();
    rowValue.payload = RowCodec.encode(ValueVarchar.get(value));
    return rowValue;
  }

  private static RegionScanTask rowTask(int limit) {
    byte[] prefix = RowPrefix.of(tabId()).toBytes();
    return new RegionScanTask("r-row",
        new KeyRange(prefix, KeyCodec.prefixEnd(prefix)),
        Collections.emptyList(), Collections.emptyList(), limit, 0);
  }

  private static RegionScanTask indexTask() {
    byte[] prefix = IndexPrefix.of(tabId(), 2).toBytes();
    return new RegionScanTask("r-index",
        new KeyRange(prefix, KeyCodec.prefixEnd(prefix)),
        Collections.emptyList(), Collections.emptyList(), 0, 0);
  }

  private static TabId tabId() {
    return TabId.of(1, 0L);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(UTF_8);
  }

  private static String toHex(byte[] bytes) {
    StringBuilder builder = new StringBuilder();
    for (byte b : bytes) {
      builder.append(String.format("%02x", b & 0xff));
    }
    return builder.toString();
  }
}
