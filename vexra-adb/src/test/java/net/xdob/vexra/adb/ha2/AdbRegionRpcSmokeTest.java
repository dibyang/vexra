package net.xdob.vexra.adb.ha2;

import net.xdob.vexra.adb.db.AdbRegionCommitRequest;
import net.xdob.vexra.adb.db.AdbRegionMutation;
import net.xdob.vexra.adb.db.AdbRegionScanRequest;
import net.xdob.vexra.adb.db.AdbRpcRegionCommitClient;
import net.xdob.vexra.adb.db.AdbPrimaryLockStatus;
import net.xdob.vexra.adb.db.AdbTxnLock;
import net.xdob.vexra.adb.db.Meta;
import net.xdob.vexra.adb.db.RowCodec;
import net.xdob.vexra.adb.db.RowValue;
import net.xdob.vexra.adb.key.DataKey;
import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.cluster.region.KeyRange;
import net.xdob.vexra.cluster.sql.RegionQueryResult;
import net.xdob.vexra.cluster.sql.RegionScanTask;
import org.h2.value.ValueVarchar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ADB region RPC 协议 smoke 测试。
 *
 * <p>该测试使用 `LocalRClient` 作为本地 RClient loopback，但上层仍通过
 * `AdbRpcRegionCommitClient`、`AdbRaftRegionCommitTransport` 和
 * `AdbRaftRegionScanClient` 发送 ADB proto。它用于在真实多进程 Raft/RPC 冒烟前，
 * 验证 commit/scan 的协议闭环没有断裂。</p>
 */
class AdbRegionRpcSmokeTest {
  @TempDir
  private Path tempDir;

  /**
   * 验证 prewrite、commit 和 region scan 可以通过同一 RClient 协议闭环完成。
   */
  @Test
  void shouldCommitAndScanVisibleRowThroughRClientProtocol() throws Exception {
    try (LocalRClient rClient = new LocalRClient(tempDir.resolve("adb")
        .toString());
         AdbRpcRegionCommitClient commitClient =
             new AdbRpcRegionCommitClient(
                 new AdbRaftRegionCommitTransport("adb", rClient), 1000)) {
      RowKey rowKey = rowKey(1);
      AdbRegionCommitRequest commitRequest = new AdbRegionCommitRequest(
          "r1", 1, "node-a", 10, 10, 20, "r1", rowKey, 3000, true,
          Collections.singletonList((DataKey) rowKey),
          Collections.singletonList(new AdbRegionMutation(rowKey,
              rowValue(10, "rpc-smoke"))),
          Collections.<Meta>emptyList());

      commitClient.prewriteAsync(commitRequest).join();
      commitClient.commitAsync(commitRequest).join();

      RegionQueryResult result = new AdbRaftRegionScanClient("adb", rClient)
          .scanAsync(scanRequest(20)).join();
      AdbPrimaryLockStatus primaryStatus =
          new AdbRaftPrimaryLockStatusReader("adb", rClient)
              .readPrimaryStatus(lock(10, rowKey));

      assertEquals(1, result.getRows().size());
      assertEquals("rpc-smoke", result.getRows().get(0).get("payload"));
      assertEquals(20, primaryStatus.getCommitTs());
    }
  }

  private static AdbRegionScanRequest scanRequest(long readTs) {
    return new AdbRegionScanRequest(new RegionScanTask("r1",
        new KeyRange(rowKey(1).toBytes(), rowKey(100).toBytes()),
        Collections.emptyList(), Collections.emptyList(), 0, readTs),
        7, readTs, false, 0);
  }

  private static RowValue rowValue(long txnId, String value) {
    RowValue rowValue = new RowValue();
    rowValue.txnId = txnId;
    rowValue.payload = RowCodec.encode(ValueVarchar.get(value));
    return rowValue;
  }

  private static AdbTxnLock lock(long txnId, RowKey primaryKey) {
    return new AdbTxnLock(txnId, primaryKey.toBytes(), primaryKey.toBytes(),
        1, "r1", 3000);
  }

  private static RowKey rowKey(long rowId) {
    return RowKey.of(TabId.of(1, 0L), rowId);
  }
}
