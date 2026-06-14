package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.RowPrefix;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.adb.ldb.LdbStore;
import net.xdob.vexra.cluster.region.KeyRange;
import net.xdob.vexra.cluster.sql.DistributedPlan;
import net.xdob.vexra.cluster.sql.RegionQueryResult;
import net.xdob.vexra.cluster.sql.RegionScanTask;
import org.h2.value.ValueVarchar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADB 分布式 region scan 执行器测试。
 *
 * <p>测试覆盖多 region 结果合并、count 合并、超时、远程异常映射和本地 bridge client。</p>
 */
class AdbDistributedRegionScanExecutorTest {
  @TempDir
  File tempDir;

  /**
   * 验证多个 region scan 结果会合并为单个行集合。
   */
  @Test
  void shouldMergeRowsFromMultipleRegionScans() throws Exception {
    RecordingClient client = new RecordingClient();
    client.complete("r1", rows("r1", row("row-1")));
    client.complete("r2", rows("r2", row("row-2")));
    AdbDistributedRegionScanExecutor executor =
        new AdbDistributedRegionScanExecutor(client);

    List<Map<String, Object>> rows = executor.executeRows(txn(),
        new DistributedPlan(Arrays.asList(task("r1"), task("r2")), false),
        1000);

    assertEquals(2, rows.size());
    assertEquals("row-1", rows.get(0).get("payload"));
    assertEquals("row-2", rows.get(1).get("payload"));
    assertEquals(Arrays.asList("r1:false", "r2:false"), client.requests);
  }

  /**
   * 验证 count-only 计划会聚合所有 region count。
   */
  @Test
  void shouldMergeCountFromMultipleRegionScans() throws Exception {
    RecordingClient client = new RecordingClient();
    client.complete("r1", count("r1", 2));
    client.complete("r2", count("r2", 3));
    AdbDistributedRegionScanExecutor executor =
        new AdbDistributedRegionScanExecutor(client);

    long count = executor.executeCount(txn(),
        new DistributedPlan(Arrays.asList(task("r1"), task("r2")), true),
        1000);

    assertEquals(5, count);
    assertEquals(Arrays.asList("r1:true", "r2:true"), client.requests);
  }

  /**
   * 验证远程 region scan 超时会映射为 SQLException 并包含 regionId。
   */
  @Test
  void shouldMapTimeoutToSqlException() {
    RecordingClient client = new RecordingClient();
    client.pending("r1");
    AdbDistributedRegionScanExecutor executor =
        new AdbDistributedRegionScanExecutor(client);

    SQLException error = assertThrows(SQLException.class,
        () -> executor.executeRows(txn(),
            new DistributedPlan(Collections.singletonList(task("r1")), false),
            10));

    assertTrue(error.getMessage().contains("Timed out"));
    assertTrue(error.getMessage().contains("r1"));
  }

  /**
   * 验证远程异常会映射为 SQLException 并保留 regionId。
   */
  @Test
  void shouldMapRemoteFailureToSqlException() {
    RecordingClient client = new RecordingClient();
    client.fail("r2", new IllegalStateException("remote unavailable"));
    AdbDistributedRegionScanExecutor executor =
        new AdbDistributedRegionScanExecutor(client);

    SQLException error = assertThrows(SQLException.class,
        () -> executor.executeRows(txn(),
            new DistributedPlan(Collections.singletonList(task("r2")), false),
            1000));

    assertTrue(error.getMessage().contains("Remote region scan failed"));
    assertTrue(error.getMessage().contains("r2"));
    assertTrue(error.getCause() instanceof IllegalStateException);
  }

  /**
   * 验证本地 bridge client 可以通过远程 executor 调用本地 adapter。
   */
  @Test
  void shouldUseLocalBridgeClientForSingleNodeExecution() throws Exception {
    try (LdbStore store = new LdbStore(new File(tempDir, "bridge-store")
        .getAbsolutePath())) {
      TxnManager manager = new TxnManager(store);
      Transaction2 writeTxn = manager.beginTransaction();
      manager.put(writeTxn, RowKey.of(tabId(), 7), rowValue("bridge-row"));
      manager.commit(writeTxn);

      AdbDistributedRegionScanExecutor executor =
          new AdbDistributedRegionScanExecutor(new AdbLocalRegionScanClient(
              new AdbLocalRegionScanExecutor(store)));

      List<Map<String, Object>> rows = executor.executeRows(
          manager.beginTransaction(),
          new DistributedPlan(Collections.singletonList(rowTask("r-local",
              manager.lastCommitTs())), false),
          1000);

      assertEquals(1, rows.size());
      assertEquals("bridge-row", rows.get(0).get("payload"));
    }
  }

  private static Transaction2 txn() {
    Transaction2 txn = new Transaction2(11, 10);
    txn.setStartTs(10);
    return txn;
  }

  private static RegionScanTask task(String regionId) {
    return new RegionScanTask(regionId,
        new KeyRange(new byte[0], new byte[0]), Collections.emptyList(),
        Collections.emptyList(), 0, 0);
  }

  private static RegionScanTask rowTask(String regionId) {
    return rowTask(regionId, 0);
  }

  private static RegionScanTask rowTask(String regionId, long readTimestamp) {
    byte[] prefix = RowPrefix.of(tabId()).toBytes();
    return new RegionScanTask(regionId,
        new KeyRange(prefix, KeyCodec.prefixEnd(prefix)),
        Collections.emptyList(), Collections.emptyList(), 0, readTimestamp);
  }

  private static TabId tabId() {
    return TabId.of(1, 0L);
  }

  private static RowValue rowValue(String value) {
    RowValue rowValue = new RowValue();
    rowValue.payload = RowCodec.encode(ValueVarchar.get(value));
    return rowValue;
  }

  private static RegionQueryResult rows(String regionId,
      Map<String, Object> row) {
    return new RegionQueryResult(regionId, Collections.singletonList(row), 0);
  }

  private static RegionQueryResult count(String regionId, long count) {
    return new RegionQueryResult(regionId, Collections.emptyList(), count);
  }

  private static Map<String, Object> row(String payload) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("payload", payload);
    return row;
  }

  private static final class RecordingClient implements AdbRegionScanClient {
    private final Map<String, CompletableFuture<RegionQueryResult>> replies =
        new LinkedHashMap<>();
    private final List<String> requests = new java.util.ArrayList<>();

    private void complete(String regionId, RegionQueryResult result) {
      replies.put(regionId, CompletableFuture.completedFuture(result));
    }

    private void pending(String regionId) {
      replies.put(regionId, new CompletableFuture<>());
    }

    private void fail(String regionId, Throwable error) {
      CompletableFuture<RegionQueryResult> future = new CompletableFuture<>();
      future.completeExceptionally(error);
      replies.put(regionId, future);
    }

    @Override
    public CompletableFuture<RegionQueryResult> scanAsync(
        AdbRegionScanRequest request) {
      requests.add(request.getTask().getRegionId() + ":"
          + request.isCountOnly());
      CompletableFuture<RegionQueryResult> reply =
          replies.get(request.getTask().getRegionId());
      if (reply == null) {
        CompletableFuture<RegionQueryResult> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalArgumentException(
            "missing reply: " + request.getTask().getRegionId()));
        return failed;
      }
      return reply;
    }
  }
}
