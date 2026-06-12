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
import java.util.ArrayList;
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
    long startTs = txn.getStartTs();
    manager.commit(txn);

    assertEquals(0, client.prewrites.size());
    assertEquals(1, client.commits.size());
    assertEquals(0, client.rollbacks.size());
    AdbRegionCommitRequest request = client.commits.get(0);
    assertEquals("r1", request.getRegionId());
    assertEquals("node-a", request.getLeaderId());
    assertEquals(3, request.getRegionEpoch());
    assertEquals(txn.getStartTs(), request.getCommitTs());
    assertEquals(1, request.getWriteKeys().size());
    assertEquals(1, request.getMutations().size());
    assertEquals(request.getWriteKeys().get(0), request.getMutations()
        .get(0).getKey());
    assertEquals(TxnState.COMMITTED, txn.getState());
  }

  /**
   * 验证跨 region 写入会通过 2PC 完成 prewrite 和 commit。
   */
  @Test
  void shouldCommitMultiRegionWriteThroughTwoPhaseCommit() throws Exception {
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

    long startTs = txn.getStartTs();
    manager.commit(txn);

    assertEquals(Arrays.asList("r1", "r2"), client.regionIds(client.prewrites));
    assertEquals(Arrays.asList("r1", "r2"), client.regionIds(client.commits));
    assertEquals(0, client.rollbacks.size());
    assertTrue(client.prewrites.get(0).isPrimaryRegion());
    assertEquals("r1", client.prewrites.get(0).getPrimaryRegionId());
    assertEquals(startTs, client.prewrites.get(0).getStartTs());
    assertEquals(1, client.prewrites.get(0).getMutations().size());
    assertEquals(1, client.prewrites.get(1).getMutations().size());
    assertEquals(TxnState.COMMITTED, txn.getState());
  }

  /**
   * 验证 prewrite 失败会回滚已经 prewrite 成功的 participant。
   */
  @Test
  void shouldRollbackPrewrittenParticipantsWhenPrewriteFails() {
    RecordingStore store = new RecordingStore();
    RecordingCommitClient client = new RecordingCommitClient();
    client.failPrewriteRegionId = "r2";
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

    assertTrue(error.getMessage().contains("prewrite failed"));
    assertEquals(Arrays.asList("r1", "r2"), client.regionIds(client.prewrites));
    assertEquals(Collections.singletonList("r1"), client.regionIds(client.rollbacks));
    assertEquals(0, client.commits.size());
    assertEquals(TxnState.PENDING, txn.getState());
  }

  /**
   * 验证 primary 已提交后 secondary commit 失败会暴露给上层，不能伪装成已回滚。
   */
  @Test
  void shouldExposeSecondaryCommitFailureAfterPrimaryCommitted() {
    RecordingStore store = new RecordingStore();
    RecordingCommitClient client = new RecordingCommitClient();
    client.failCommitRegionId = "r2";
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

    assertTrue(error.getMessage().contains("commit failed"));
    assertEquals(Arrays.asList("r1", "r2"), client.regionIds(client.prewrites));
    assertEquals(Arrays.asList("r1", "r2"), client.regionIds(client.commits));
    assertEquals(0, client.rollbacks.size());
    assertEquals(TxnState.PENDING, txn.getState());
  }

  /**
   * 验证 commitTs 不大于 startTs 时会在任何 region prewrite 前失败。
   */
  @Test
  void shouldRejectInvalidCommitTimestampBeforePrewrite() {
    RecordingStore store = new RecordingStore();
    store.counter = 0;
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
    assertEquals(0, client.prewrites.size());
    assertEquals(0, client.commits.size());
    assertEquals(0, client.rollbacks.size());
    assertEquals(TxnState.PENDING, txn.getState());
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
    assertEquals(0, client.commits.size());
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
    assertEquals(0, client.commits.size());
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
    assertEquals(1, client.commits.size());
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
    private final List<AdbRegionCommitRequest> prewrites = new ArrayList<>();
    private final List<AdbRegionCommitRequest> commits = new ArrayList<>();
    private final List<AdbRegionCommitRequest> rollbacks = new ArrayList<>();
    private SQLException failure;
    private String failPrewriteRegionId;
    private String failCommitRegionId;

    @Override
    public CompletableFuture<Void> prewriteAsync(
        AdbRegionCommitRequest request) {
      prewrites.add(request);
      if (request.getRegionId().equals(failPrewriteRegionId)) {
        return failed(new SQLException("prewrite failed: "
            + request.getRegionId()));
      }
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> commitAsync(AdbRegionCommitRequest request) {
      commits.add(request);
      if (request.getRegionId().equals(failCommitRegionId)) {
        return failed(new SQLException("commit failed: "
            + request.getRegionId()));
      }
      CompletableFuture<Void> future = new CompletableFuture<>();
      if (failure != null) {
        future.completeExceptionally(failure);
      } else {
        future.complete(null);
      }
      return future;
    }

    @Override
    public CompletableFuture<Void> rollbackAsync(
        AdbRegionCommitRequest request) {
      rollbacks.add(request);
      return CompletableFuture.completedFuture(null);
    }

    private List<String> regionIds(List<AdbRegionCommitRequest> requests) {
      List<String> regionIds = new ArrayList<>();
      for (AdbRegionCommitRequest request : requests) {
        regionIds.add(request.getRegionId());
      }
      return regionIds;
    }

    private static CompletableFuture<Void> failed(Throwable error) {
      CompletableFuture<Void> future = new CompletableFuture<>();
      future.completeExceptionally(error);
      return future;
    }
  }

  private static final class RecordingStore implements DbStore {
    private long counter = 10;

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
