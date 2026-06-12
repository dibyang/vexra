package net.xdob.vexra.adb.ha2;

import com.google.protobuf.ByteString;
import net.xdob.vexra.adb.db.AdbRegionScanRequest;
import net.xdob.vexra.adb.db.RowCodec;
import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.cluster.region.KeyRange;
import net.xdob.vexra.cluster.sql.RegionQueryResult;
import net.xdob.vexra.cluster.sql.RegionScanTask;
import net.xdob.vexra.proto.adb.ReadRequest;
import net.xdob.vexra.proto.adb.ReadResponse;
import net.xdob.vexra.proto.adb.RegionScanResult;
import net.xdob.vexra.proto.adb.RegionVisibleRow;
import net.xdob.vexra.proto.adb.WriteRequest;
import net.xdob.vexra.proto.adb.WriteResponse;
import net.xdob.vexra.util.Proto2Util;
import org.h2.value.ValueVarchar;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADB Raft region scan client 测试。
 *
 * <p>测试覆盖 ADB-Prod-01 的专用 region scan RPC transport：RegionScan 请求字段映射、
 * 分页、count-only 聚合和失败响应映射。</p>
 */
class AdbRaftRegionScanClientTest {
  /**
   * 验证 scan client 会通过 Raft ReadRequest.RegionScan 分页读取可见行。
   */
  @Test
  void shouldScanVisibleRowsThroughRaftRegionScanRequests() {
    RecordingRClient client = new RecordingRClient();
    RowKey row1 = rowKey(1);
    RowKey row2 = rowKey(2);
    client.pages.add(RegionScanResult.newBuilder()
        .addRows(row(row1, "visible-1"))
        .setCount(1)
        .setHasMore(true)
        .setResumeKey(ByteString.copyFrom(row1.toBytes()))
        .build());
    client.pages.add(RegionScanResult.newBuilder()
        .addRows(row(row2, "visible-2"))
        .setCount(1)
        .build());
    AdbRaftRegionScanClient scanClient =
        new AdbRaftRegionScanClient("adb", client, 1);

    RegionQueryResult result = scanClient.scanAsync(request(false, 20)).join();

    assertEquals("r1", result.getRegionId());
    assertEquals(2, result.getRows().size());
    assertEquals("visible-1", result.getRows().get(0).get("payload"));
    assertEquals("visible-2", result.getRows().get(1).get("payload"));
    assertEquals(2, client.requests.size());
    assertTrue(client.requests.get(0).hasRegionScan());
    assertEquals("adb", client.requests.get(0).getDbName());
    assertEquals(1, client.requests.get(0).getRegionScan().getLimit());
    assertTrue(client.requests.get(0).getRegionScan().getResumeKey()
        .isEmpty());
    assertFalse(client.requests.get(1).getRegionScan().getResumeKey()
        .isEmpty());
  }

  /**
   * 验证 count-only 请求会累加每页 count。
   */
  @Test
  void shouldReturnCountOnlyResult() {
    RecordingRClient client = new RecordingRClient();
    client.pages.add(RegionScanResult.newBuilder()
        .setCount(2)
        .setHasMore(true)
        .setResumeKey(ByteString.copyFrom(rowKey(2).toBytes()))
        .build());
    client.pages.add(RegionScanResult.newBuilder()
        .setCount(1)
        .build());
    AdbRaftRegionScanClient scanClient =
        new AdbRaftRegionScanClient("adb", client, 10);

    RegionQueryResult result = scanClient.scanAsync(request(true, 20)).join();

    assertTrue(result.getRows().isEmpty());
    assertEquals(3, result.getCount());
  }

  /**
   * 验证可见删除由 region 状态机处理后，client 能接受空行结果。
   */
  @Test
  void shouldAcceptEmptyRowsWhenRegionResolvedVisibleDelete() {
    RecordingRClient client = new RecordingRClient();
    client.pages.add(RegionScanResult.newBuilder().build());
    AdbRaftRegionScanClient scanClient =
        new AdbRaftRegionScanClient("adb", client, 10);

    RegionQueryResult result = scanClient.scanAsync(request(false, 20)).join();

    assertTrue(result.getRows().isEmpty());
  }

  /**
   * 验证 Raft read 失败会映射为 SQLException。
   */
  @Test
  void shouldMapFailedReadResponseToSQLException() {
    RecordingRClient client = new RecordingRClient();
    client.failure = new SQLException("region read failed");
    AdbRaftRegionScanClient scanClient =
        new AdbRaftRegionScanClient("adb", client, 10);

    java.util.concurrent.CompletionException error = assertThrows(
        java.util.concurrent.CompletionException.class,
        () -> scanClient.scanAsync(request(false, 20)).join());

    assertTrue(error.getCause() instanceof SQLException);
    assertTrue(error.getCause().getMessage().contains("region read failed"));
  }

  private static AdbRegionScanRequest request(boolean countOnly, long readTs) {
    return new AdbRegionScanRequest(new RegionScanTask("r1",
        new KeyRange(rowKey(1).toBytes(), rowKey(100).toBytes()),
        Collections.emptyList(), Collections.emptyList(), 0, readTs),
        7, readTs, countOnly, 0);
  }

  private static RegionVisibleRow row(RowKey key, String payload) {
    return RegionVisibleRow.newBuilder()
        .setRowId(key.getRowId())
        .setKey(ByteString.copyFrom(key.toBytes()))
        .setPayload(ByteString.copyFrom(RowCodec.encode(
            ValueVarchar.get(payload))))
        .build();
  }

  private static RowKey rowKey(long rowId) {
    return RowKey.of(TabId.of(1, 0L), rowId);
  }

  private static final class RecordingRClient implements RClient {
    private final Queue<RegionScanResult> pages = new ArrayDeque<>();
    private final java.util.List<ReadRequest> requests =
        new java.util.ArrayList<>();
    private SQLException failure;

    @Override
    public ReadResponse sendReadRequest(ReadRequest request) {
      requests.add(request);
      if (failure != null) {
        return ReadResponse.newBuilder()
            .setSuccess(false)
            .setEx(Proto2Util.toThrowable2Proto(failure))
            .build();
      }
      RegionScanResult page = pages.isEmpty()
          ? RegionScanResult.newBuilder().build() : pages.remove();
      return ReadResponse.newBuilder()
          .setSuccess(true)
          .setRegionScanResult(page)
          .build();
    }

    @Override
    public WriteResponse sendWriteRequest(WriteRequest request) {
      throw new UnsupportedOperationException("write is not used");
    }

    @Override
    public CompletableFuture<ReadResponse> sendReadRequestAsync(
        ReadRequest request) {
      return CompletableFuture.completedFuture(sendReadRequest(request));
    }

    @Override
    public CompletableFuture<WriteResponse> sendWriteRequestAsync(
        WriteRequest request) {
      throw new UnsupportedOperationException("write is not used");
    }

    @Override
    public void close() throws IOException {
    }
  }
}
