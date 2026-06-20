package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.cluster.region.KeyRange;
import net.xdob.vexra.cluster.region.RegionMetadata;
import net.xdob.vexra.ha.ReplicaRole;
import net.xdob.vexra.ha.VirtualNodeMetadata;
import net.xdob.vexra.ha.VirtualNodeReplica;
import org.h2.value.ValueVarchar;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADB runtime session context 测试。
 *
 * <p>测试覆盖控制面 route snapshot 刷新、外部 TSO 接入 TxnManager 以及 session
 * context 的安装/解绑行为。</p>
 */
class AdbRuntimeSessionContextTest {
  /**
   * 验证控制面 TSO 会驱动事务 startTs 和 commitTs 单调递增。
   */
  @Test
  void shouldUseControlPlaneTimestampProviderInTxnManager()
      throws Exception {
    RecordingStore store = new RecordingStore();
    TxnManager manager = new TxnManager(store);
    InMemoryAdbControlPlaneClient controlPlane =
        new InMemoryAdbControlPlaneClient(
            Collections.singletonList(region("r1", "node-a")), 100);
    RecordingCommitClient commitClient = new RecordingCommitClient();
    new AdbRuntimeSessionContext(manager, controlPlane, commitClient);

    Transaction2 txn = manager.beginTransaction();
    assertEquals(101, txn.getStartTs());

    txn.recordWrite(rowKey(1), rowValue("value"));
    manager.commit(txn);

    assertEquals(102, commitClient.request.getCommitTs());
    assertEquals(102, txn.getStartTs());
    assertEquals(102, manager.lastCommitTs());
  }

  /**
   * 验证 session 刷新 route snapshot 后，新提交使用新的 region router。
   */
  @Test
  void shouldRefreshRouteSnapshotForNewWrites() throws Exception {
    RecordingStore store = new RecordingStore();
    TxnManager manager = new TxnManager(store);
    InMemoryAdbControlPlaneClient controlPlane =
        new InMemoryAdbControlPlaneClient(
            Collections.singletonList(region("r1", "node-a")), 10);
    RecordingCommitClient commitClient = new RecordingCommitClient();
    AdbRuntimeSessionContext context = new AdbRuntimeSessionContext(manager,
        controlPlane, commitClient);

    assertEquals(1, context.getSnapshot().getRouteEpoch());

    controlPlane.publishRegions(Collections.singletonList(region("r2", "node-b")));
    AdbControlPlaneSnapshot refreshed = context.refreshRouteSnapshot();
    assertEquals(2, refreshed.getRouteEpoch());

    Transaction2 txn = manager.beginTransaction();
    txn.recordWrite(rowKey(2), rowValue("value"));
    manager.commit(txn);

    assertEquals("r2", commitClient.request.getRegionId());
    assertEquals("node-b", commitClient.request.getLeaderId());
  }

  /**
   * 验证 route watch 发现 epoch 变化后，session 可以自动刷新路由。
   */
  @Test
  void shouldRefreshRouteSnapshotWhenWatchReportsChange() {
    RecordingStore store = new RecordingStore();
    TxnManager manager = new TxnManager(store);
    InMemoryAdbControlPlaneClient controlPlane =
        new InMemoryAdbControlPlaneClient(
            Collections.singletonList(region("r1", "node-a")), 10);
    AdbRuntimeSessionContext context = new AdbRuntimeSessionContext(manager,
        controlPlane, new RecordingCommitClient());

    assertFalse(context.refreshRouteSnapshotIfChanged());
    controlPlane.publishRegions(Collections.singletonList(
        region("r2", "node-b")));

    assertTrue(context.refreshRouteSnapshotIfChanged());
    assertEquals(2, context.getSnapshot().getRouteEpoch());
    assertEquals("r2", context.getSnapshot().getRegions().get(0)
        .getRegionId());
  }

  /**
   * 验证控制面 route TTL 过期后，runtime 安装的 region commit 会拒绝写入。
   */
  @Test
  void shouldRejectWriteWhenControlPlaneRouteTtlExpires() {
    RecordingStore store = new RecordingStore();
    TxnManager manager = new TxnManager(store);
    InMemoryAdbControlPlaneClient controlPlane =
        new InMemoryAdbControlPlaneClient(
            Collections.singletonList(region("r1", "node-a")), 10);
    RecordingCommitClient commitClient = new RecordingCommitClient();
    ManualClock clock = new ManualClock(1000);
    new AdbRuntimeSessionContext(manager, controlPlane, commitClient,
        100, clock);

    clock.now = 1101;
    Transaction2 txn = manager.beginTransaction();
    txn.recordWrite(rowKey(3), rowValue("value"));

    SQLException error = assertThrows(SQLException.class,
        () -> manager.commit(txn));
    assertTrue(error.getMessage().contains("route snapshot expired"));
    assertNull(commitClient.request);
  }

  /**
   * 验证 detach 会恢复 TxnManager 默认单机路径。
   */
  @Test
  void shouldDetachRuntimeContextAndRestoreDefaults() throws Exception {
    RecordingStore store = new RecordingStore();
    TxnManager manager = new TxnManager(store);
    InMemoryAdbControlPlaneClient controlPlane =
        new InMemoryAdbControlPlaneClient(
            Collections.singletonList(region("r1", "node-a")), 50);
    AdbRuntimeSessionContext context = new AdbRuntimeSessionContext(manager,
        controlPlane, new RecordingCommitClient());

    context.detach();

    assertNull(context.getSnapshot());
    assertNull(manager.getTimestampProvider());
    assertNull(manager.getRegionCommitCoordinator());
    assertSame(AdbRegionReadRouter.NOOP, manager.getRegionReadRouter());
  }

  /**
   * 验证发布空 region 快照会被拒绝。
   */
  @Test
  void shouldRejectEmptyRegionSnapshot() {
    assertThrows(IllegalArgumentException.class,
        () -> new InMemoryAdbControlPlaneClient(Collections.emptyList(), 1));
  }

  private static RowKey rowKey(long rowId) {
    return RowKey.of(TabId.of(1, 0L), rowId);
  }

  private static RowValue rowValue(String value) {
    RowValue rowValue = new RowValue();
    rowValue.payload = RowCodec.encode(ValueVarchar.get(value));
    return rowValue;
  }

  private static RegionMetadata region(String regionId, String leaderId) {
    return new RegionMetadata(regionId, new KeyRange(new byte[0], new byte[0]),
        1, new VirtualNodeMetadata("vn-" + regionId, 1, leaderId,
        Arrays.asList(
            new VirtualNodeReplica("node-a", ReplicaRole.DATA_VOTER),
            new VirtualNodeReplica("node-b", ReplicaRole.DATA_VOTER),
            new VirtualNodeReplica("witness-a", ReplicaRole.WITNESS_VOTER)),
        0, 0, 0));
  }

  private static final class RecordingCommitClient
      implements AdbRegionCommitClient {
    private AdbRegionCommitRequest request;

    @Override
    public CompletableFuture<Void> commitAsync(AdbRegionCommitRequest request) {
      this.request = request;
      return CompletableFuture.completedFuture(null);
    }
  }

  private static final class ManualClock
      implements java.util.function.LongSupplier {
    private long now;

    private ManualClock(long now) {
      this.now = now;
    }

    @Override
    public long getAsLong() {
      return now;
    }
  }

  private static final class RecordingStore implements DbStore {
    private long counter;

    @Override
    public byte[] get(byte[] key) {
      return null;
    }

    @Override
    public void put(byte[] key, byte[] value) {
    }

    @Override
    public long addLong(byte[] key, long operand) {
      counter += operand;
      return counter;
    }

    @Override
    public Optional<Long> getLong(byte[] key) {
      return Optional.of(counter);
    }

    @Override
    public void putLong(byte[] key, long value) {
    }

    @Override
    public void delete(byte[] key) {
    }

    @Override
    public void deleteRange(byte[] startKey, byte[] endKey) {
    }

    @Override
    public byte[] get(byte cfId, byte[] key) {
      return null;
    }

    @Override
    public void put(byte cfId, byte[] key, byte[] value) {
    }

    @Override
    public long addLong(byte cfId, byte[] key, long delta) {
      counter += delta;
      return counter;
    }

    @Override
    public Optional<Long> getLong(byte cfId, byte[] key) {
      return Optional.of(counter);
    }

    @Override
    public void putLong(byte cfId, byte[] key, long value) {
    }

    @Override
    public void delete(byte cfId, byte[] key) {
    }

    @Override
    public void deleteRange(byte cfId, byte[] startKey, byte[] endKey) {
    }

    @Override
    public void checkpoint(String targetDir) throws IOException {
    }

    @Override
    public void restore(String sourceDir) throws IOException {
    }

    @Override
    public void writeBatch(WriteBatchConsumer consumer) {
      throw new UnsupportedOperationException("writeBatch is not used");
    }

    @Override
    public void rollback(long txnId) {
    }

    @Override
    public CompletableFuture<Void> commitAsync(long txnId, long commitTs,
        List<Meta> metas) {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public VersionScanSource openVersionScanSource(ScanDirection direction) {
      throw new UnsupportedOperationException("version scan is not used");
    }

    @Override
    public VersionScanSource openVersionScanSource(byte cfId,
        ScanDirection direction) {
      throw new UnsupportedOperationException("version scan is not used");
    }

    @Override
    public void close() throws IOException {
    }
  }
}
