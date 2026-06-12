package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.cluster.region.KeyRange;
import net.xdob.vexra.cluster.region.RegionMetadata;
import net.xdob.vexra.cluster.region.RegionRouter;
import net.xdob.vexra.cluster.region.RegionWitnessBinding;
import net.xdob.vexra.ha.ReplicaRole;
import net.xdob.vexra.ha.VirtualNodeMetadata;
import net.xdob.vexra.ha.VirtualNodeReplica;
import net.xdob.vexra.ha.witness.FileWitnessStateStore;
import net.xdob.vexra.ha.witness.WitnessStateManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADB region 写入 gate 回归测试。
 *
 * <p>测试覆盖 region-aware gate 的多数派约束，以及 `TxnManager.commit` 在
 * gate 失败时不会进入 commitTs 分配和 durable commit。</p>
 */
class AdbRegionWriteGateTest {
  @TempDir
  Path tempDir;

  /**
   * 验证 data+witness 多数派可以放行 region-aware ADB 写入。
   */
  @Test
  void shouldAllowWriteWhenRegionQuorumIsSatisfied() throws SQLException {
    RegionAwareAdbWriteGate gate = gate("node-a", "witness-a");

    assertDoesNotThrow(() -> gate.beforeCommit(txnWithWrite(),
        Collections.singletonList(rowKey())));
  }

  /**
   * 验证缺少多数派时 region-aware ADB 写入会在提交前被拒绝。
   */
  @Test
  void shouldRejectWriteWhenRegionQuorumIsMissing() {
    RegionAwareAdbWriteGate gate = gate("node-a");

    SQLException error = assertThrows(SQLException.class,
        () -> gate.beforeCommit(txnWithWrite(),
            Collections.singletonList(rowKey())));

    assertTrue(error.getMessage().contains("quorum"));
  }

  /**
   * 验证 TxnManager 在 gate 失败时不分配 commitTs，也不调用 store commit。
   */
  @Test
  void shouldStopTxnCommitBeforeCommitTsWhenGateFails() {
    RecordingStore store = new RecordingStore();
    TxnManager manager = new TxnManager(store);
    manager.setRegionWriteGate((txn, writeKeys) -> {
      throw new SQLException("quorum missing");
    });

    Transaction2 txn = txnWithWrite();

    SQLException error = assertThrows(SQLException.class,
        () -> manager.commit(txn));

    assertEquals("quorum missing", error.getMessage());
    assertEquals(TxnState.PENDING, txn.getState());
    assertEquals(0, store.commitCalls);
    assertEquals(0, store.counterUpdates);
  }

  /**
   * 验证 null gate 会恢复为默认 no-op，以便单机模式回滚。
   */
  @Test
  void shouldResetNullGateToNoop() throws SQLException {
    RecordingStore store = new RecordingStore();
    TxnManager manager = new TxnManager(store);

    manager.setRegionWriteGate(null);

    assertSame(AdbRegionWriteGate.NOOP, manager.getRegionWriteGate());
    assertDoesNotThrow(() -> manager.getRegionWriteGate()
        .beforeCommit(txnWithWrite(), Collections.singletonList(rowKey())));
  }

  private RegionAwareAdbWriteGate gate(String... acknowledgedReplicaIds) {
    RegionWitnessBinding binding = new RegionWitnessBinding(
        new WitnessStateManager(new FileWitnessStateStore(tempDir)));
    return new RegionAwareAdbWriteGate(
        new RegionRouter(Collections.singletonList(region())),
        binding,
        () -> "node-a",
        () -> Arrays.asList(acknowledgedReplicaIds));
  }

  private static Transaction2 txnWithWrite() {
    Transaction2 txn = new Transaction2(10, 9);
    RowValue value = new RowValue();
    value.payload = new byte[] {1};
    txn.recordWrite(rowKey(), value);
    return txn;
  }

  private static RowKey rowKey() {
    return RowKey.of(TabId.of(1, 1L), 100L);
  }

  private static RegionMetadata region() {
    return new RegionMetadata("r1",
        new KeyRange(new byte[0], new byte[0]), 1,
        new VirtualNodeMetadata("vn-r1", 1, "node-a",
            Arrays.asList(
                new VirtualNodeReplica("node-a", ReplicaRole.DATA_VOTER),
                new VirtualNodeReplica("node-b", ReplicaRole.DATA_VOTER),
                new VirtualNodeReplica("witness-a", ReplicaRole.WITNESS_VOTER)),
            0, 0, 0));
  }

  private static final class RecordingStore implements DbStore {
    private int commitCalls;
    private int counterUpdates;

    @Override
    public byte[] get(byte[] key) {
      return null;
    }

    @Override
    public void put(byte[] key, byte[] value) {
    }

    @Override
    public long addLong(byte[] key, long operand) {
      counterUpdates++;
      return counterUpdates;
    }

    @Override
    public Optional<Long> getLong(byte[] key) {
      return Optional.empty();
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
      counterUpdates++;
      return counterUpdates;
    }

    @Override
    public Optional<Long> getLong(byte cfId, byte[] key) {
      return Optional.empty();
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
      commitCalls++;
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
