package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.adb.key.DataKey;
import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.adb.ldb.LdbStore;
import net.xdob.vexra.cluster.region.KeyRange;
import net.xdob.vexra.cluster.region.RegionMetadata;
import net.xdob.vexra.cluster.region.RegionRouter;
import net.xdob.vexra.ha.ReplicaRole;
import net.xdob.vexra.ha.VirtualNodeMetadata;
import net.xdob.vexra.ha.VirtualNodeReplica;
import org.h2.value.ValueVarchar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADB region commit 协调器测试。
 *
 * <p>测试覆盖 TxnManager 到 region commit client 的提交桥接、单 region 校验、
 * leader/epoch 校验、远程失败映射和本地 bridge 持久化。</p>
 */
class AdbRegionCommitCoordinatorTest {
  @TempDir
  File tempDir;

  /**
   * 验证本地 bridge client 可以通过 region commit coordinator 持久化提交。
   */
  @Test
  void shouldCommitSingleRegionWriteThroughLocalBridge() throws Exception {
    try (LdbStore store = new LdbStore(new File(tempDir, "commit-store")
        .getAbsolutePath())) {
      TxnManager manager = new TxnManager(store);
      manager.setRegionCommitCoordinator(new AdbRegionCommitCoordinator(
          new RegionRouter(Collections.singletonList(region("r1",
              new KeyRange(new byte[0], new byte[0]), "node-a", 1, 1))),
          new AdbLocalRegionCommitClient(store)));

      Transaction2 txn = manager.beginTransaction();
      RowKey key = rowKey(1);
      manager.put(txn, key, rowValue("committed-through-region"));
      manager.commit(txn);

      RowValue visible = manager.getVisible(manager.beginTransaction(), key);
      assertNotNull(visible);
      assertEquals("committed-through-region",
          RowCodec.decode(visible.payload).getString());
      assertEquals(TxnState.COMMITTED, txn.getState());
    }
  }

  /**
   * 验证单 region 提交会把 region、leader、epoch 和事务时间戳传给 client。
   */
  @Test
  void shouldSendRegionCommitRequestToClient() throws Exception {
    RecordingStore store = new RecordingStore();
    RecordingCommitClient client = new RecordingCommitClient();
    TxnManager manager = new TxnManager(store);
    manager.setRegionCommitCoordinator(new AdbRegionCommitCoordinator(
        new RegionRouter(Collections.singletonList(region("r1",
            new KeyRange(new byte[0], new byte[0]), "node-a", 3, 3))),
        client));

    Transaction2 txn = txnWithWrites(rowKey(1));
    manager.commit(txn);

    assertNotNull(client.request);
    assertEquals("r1", client.request.getRegionId());
    assertEquals("node-a", client.request.getLeaderId());
    assertEquals(3, client.request.getRegionEpoch());
    assertEquals(txn.getStartTs(), client.request.getCommitTs());
    assertEquals(1, client.request.getWriteKeys().size());
    assertEquals(TxnState.COMMITTED, txn.getState());
  }

  /**
   * 验证跨 region 写入在 2PC 阶段前会被拒绝，事务状态恢复为 PENDING。
   */
  @Test
  void shouldRejectMultiRegionCommitBeforeTwoPhaseCommit() {
    RecordingStore store = new RecordingStore();
    RecordingCommitClient client = new RecordingCommitClient();
    RowKey split = rowKey(50);
    TxnManager manager = new TxnManager(store);
    manager.setRegionCommitCoordinator(new AdbRegionCommitCoordinator(
        new RegionRouter(Arrays.asList(
            region("r1", new KeyRange(new byte[0], split.toBytes()),
                "node-a", 1, 1),
            region("r2", new KeyRange(split.toBytes(), new byte[0]),
                "node-b", 1, 1))),
        client));

    Transaction2 txn = txnWithWrites(rowKey(1), rowKey(100));

    SQLException error = assertThrows(SQLException.class,
        () -> manager.commit(txn));

    assertTrue(error.getMessage().contains("Commit failed"));
    assertEquals(TxnState.PENDING, txn.getState());
    assertNull(client.request);
  }

  /**
   * 验证缺少 leader 的 region 不能进入 commit client。
   */
  @Test
  void shouldRejectRegionWithoutLeader() {
    RecordingStore store = new RecordingStore();
    RecordingCommitClient client = new RecordingCommitClient();
    TxnManager manager = new TxnManager(store);
    manager.setRegionCommitCoordinator(new AdbRegionCommitCoordinator(
        new RegionRouter(Collections.singletonList(region("r1",
            new KeyRange(new byte[0], new byte[0]), "", 1, 1))),
        client));

    Transaction2 txn = txnWithWrites(rowKey(1));

    SQLException error = assertThrows(SQLException.class,
        () -> manager.commit(txn));

    assertTrue(error.getMessage().contains("Commit failed"));
    assertEquals(TxnState.PENDING, txn.getState());
    assertNull(client.request);
  }

  /**
   * 验证 region epoch 与副本 epoch 不一致时拒绝提交。
   */
  @Test
  void shouldRejectEpochMismatch() {
    RecordingStore store = new RecordingStore();
    RecordingCommitClient client = new RecordingCommitClient();
    TxnManager manager = new TxnManager(store);
    manager.setRegionCommitCoordinator(new AdbRegionCommitCoordinator(
        new RegionRouter(Collections.singletonList(region("r1",
            new KeyRange(new byte[0], new byte[0]), "node-a", 2, 1))),
        client));

    Transaction2 txn = txnWithWrites(rowKey(1));

    SQLException error = assertThrows(SQLException.class,
        () -> manager.commit(txn));

    assertTrue(error.getMessage().contains("Commit failed"));
    assertEquals(TxnState.PENDING, txn.getState());
    assertNull(client.request);
  }

  /**
   * 验证 region commit client 失败会映射到 commit 失败并恢复事务状态。
   */
  @Test
  void shouldRestoreTxnStateWhenRegionClientFails() {
    RecordingStore store = new RecordingStore();
    RecordingCommitClient client = new RecordingCommitClient();
    client.failure = new SQLException("region apply failed");
    TxnManager manager = new TxnManager(store);
    manager.setRegionCommitCoordinator(new AdbRegionCommitCoordinator(
        new RegionRouter(Collections.singletonList(region("r1",
            new KeyRange(new byte[0], new byte[0]), "node-a", 1, 1))),
        client));

    Transaction2 txn = txnWithWrites(rowKey(1));

    SQLException error = assertThrows(SQLException.class,
        () -> manager.commit(txn));

    assertEquals("region apply failed", error.getMessage());
    assertEquals(TxnState.PENDING, txn.getState());
    assertNotNull(client.request);
  }

  private static Transaction2 txnWithWrites(DataKey... keys) {
    Transaction2 txn = new Transaction2(10, 9);
    txn.setState(TxnState.PENDING);
    for (DataKey key : keys) {
      txn.recordWrite(key, rowValue("value-" + key.getRowId()));
    }
    return txn;
  }

  private static RowKey rowKey(long rowId) {
    return RowKey.of(TabId.of(1, 0L), rowId);
  }

  private static RowValue rowValue(String value) {
    RowValue rowValue = new RowValue();
    rowValue.payload = RowCodec.encode(ValueVarchar.get(value));
    return rowValue;
  }

  private static RegionMetadata region(String regionId, KeyRange range,
      String leaderId, long regionEpoch, long replicaEpoch) {
    return new RegionMetadata(regionId, range, regionEpoch,
        new VirtualNodeMetadata("vn-" + regionId, replicaEpoch, leaderId,
            Arrays.asList(
                new VirtualNodeReplica("node-a", ReplicaRole.DATA_VOTER),
                new VirtualNodeReplica("node-b", ReplicaRole.DATA_VOTER),
                new VirtualNodeReplica("witness-a", ReplicaRole.WITNESS_VOTER)),
            0, 0, 0));
  }

  private static final class RecordingCommitClient
      implements AdbRegionCommitClient {
    private AdbRegionCommitRequest request;
    private SQLException failure;

    @Override
    public CompletableFuture<Void> commitAsync(AdbRegionCommitRequest request) {
      this.request = request;
      CompletableFuture<Void> future = new CompletableFuture<>();
      if (failure != null) {
        future.completeExceptionally(failure);
      } else {
        future.complete(null);
      }
      return future;
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
      throw new UnsupportedOperationException("store commit should not be used");
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
