package net.xdob.vexra.adb.db;

import com.google.protobuf.ByteString;
import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.adb.key.VersionKey;
import net.xdob.vexra.adb.ldb.LdbStore;
import net.xdob.vexra.proto.adb.RegionScan;
import net.xdob.vexra.proto.adb.RegionScanResult;
import org.h2.value.ValueVarchar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADB region scan reader 测试。
 *
 * <p>验证专用 RegionScan proto 下沉到状态机后，region 内能够完成基础 MVCC
 * 可见性归并、分页和删除遮蔽。</p>
 */
class AdbRegionScanReaderTest {
  @TempDir
  private Path tempDir;

  /**
   * 验证 reader 会跳过太新的版本，返回 read timestamp 可见版本。
   */
  @Test
  void shouldResolveVisibleVersionsInsideRegion() throws Exception {
    try (LdbStore store = new LdbStore(tempDir.resolve("visible").toString())) {
      RowKey row1 = rowKey(1);
      RowKey row2 = rowKey(2);
      putCommitted(store, row1, 30, "too-new");
      putCommitted(store, row1, 10, "visible-1");
      putCommitted(store, row2, 11, "visible-2");

      RegionScanResult first = AdbRegionScanReader.scan(store,
          scan(20, 1, null));
      RegionScanResult second = AdbRegionScanReader.scan(store,
          scan(20, 1, first.getResumeKey()));

      assertEquals(1, first.getRowsCount());
      assertEquals("visible-1", decode(first.getRows(0).getPayload()
          .toByteArray()));
      assertTrue(first.getHasMore());
      assertEquals(1, second.getRowsCount());
      assertEquals("visible-2", decode(second.getRows(0).getPayload()
          .toByteArray()));
      assertFalse(second.getHasMore());
    }
  }

  /**
   * 验证 count-only 会在 region 内统计可见非删除行。
   */
  @Test
  void shouldCountVisibleRowsOnly() throws Exception {
    try (LdbStore store = new LdbStore(tempDir.resolve("count").toString())) {
      putCommitted(store, rowKey(1), 10, "visible-1");
      putCommitted(store, rowKey(2), 30, "too-new");
      putCommitted(store, rowKey(3), 12, "visible-3");

      RegionScanResult result = AdbRegionScanReader.scan(store,
          scan(20, 10, null).toBuilder().setCountOnly(true).build());

      assertEquals(0, result.getRowsCount());
      assertEquals(2, result.getCount());
    }
  }

  /**
   * 验证可见删除版本会遮蔽更老版本。
   */
  @Test
  void shouldTreatVisibleDeleteAsResolvedLogicalKey() throws Exception {
    try (LdbStore store = new LdbStore(tempDir.resolve("delete").toString())) {
      RowKey row1 = rowKey(1);
      putCommitted(store, row1, 15, null);
      putCommitted(store, row1, 10, "older");

      RegionScanResult result = AdbRegionScanReader.scan(store,
          scan(20, 10, null));

      assertEquals(0, result.getRowsCount());
      assertEquals(0, result.getCount());
    }
  }

  private static RegionScan scan(long startTs, int limit, ByteString resumeKey) {
    RegionScan.Builder builder = RegionScan.newBuilder()
        .setRegionId("r1")
        .setStartKey(ByteString.copyFrom(rowKey(1).toBytes()))
        .setEndKey(ByteString.copyFrom(rowKey(100).toBytes()))
        .setStartTs(startTs)
        .setLimit(limit);
    if (resumeKey != null && !resumeKey.isEmpty()) {
      builder.setResumeKey(resumeKey);
    }
    return builder.build();
  }

  private static void putCommitted(LdbStore store, RowKey key, long commitTs,
      String payload) throws Exception {
    store.writeBatch(batch -> batch.put(VersionKey.of(key, true, commitTs)
        .toBytes(), RowValue.encodeValue(rowValue(commitTs, payload))));
  }

  private static RowValue rowValue(long commitTs, String payload) {
    RowValue rowValue = new RowValue();
    rowValue.txnId = commitTs;
    rowValue.commitTs = commitTs;
    rowValue.deleted = payload == null;
    rowValue.payload = payload == null ? null
        : RowCodec.encode(ValueVarchar.get(payload));
    return rowValue;
  }

  private static String decode(byte[] payload) {
    return RowCodec.decode(payload).getString();
  }

  private static RowKey rowKey(long rowId) {
    return RowKey.of(TabId.of(1, 0L), rowId);
  }
}
