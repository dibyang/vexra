package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.adb.key.VersionKey;
import net.xdob.vexra.adb.ldb.LdbStore;
import net.xdob.vexra.cluster.region.KeyRange;
import net.xdob.vexra.cluster.region.RegionMetadata;
import net.xdob.vexra.ha.ReplicaRole;
import net.xdob.vexra.ha.VirtualNodeMetadata;
import net.xdob.vexra.ha.VirtualNodeReplica;
import org.h2.value.ValueVarchar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADB-Prod-02 验收级闭环测试。
 *
 * <p>该测试把 primary 已提交后的 secondary 前滚、跨 region primary-status
 * 结果、长事务 safe point 保护和租约保护的集群 committed version GC cycle
 * 串在同一条真实 LDB store 流程里，作为 ADB-Prod-02 lock/GC 协同语义的回归
 * 基线。它不启动真实多进程或网络 RPC。</p>
 */
class AdbProd02AcceptanceTest {
  @TempDir
  private Path tempDir;

  /**
   * 验证部分提交事务可以通过 primary 状态前滚 secondary，且后续 GC 会保护长事务。
   */
  @Test
  void shouldRollForwardPartialCommitAndProtectLongTransactionDuringGc()
      throws Exception {
    try (LdbStore store = new LdbStore(
        tempDir.resolve("prod02-lock-gc-acceptance").toString())) {
      RowKey primary = rowKey(1);
      RowKey secondary = rowKey(2);
      long txnId = 100;
      long startTs = 30;
      long commitTs = 40;

      putCommitted(store, secondary, 10, "old-before-safe-point");
      putCommitted(store, secondary, 22, "visible-for-active-snapshot");
      prewrite(store, txnId, primary, primary, startTs, 5, "primary-new");
      store.commitAsync(txnId, commitTs, Collections.emptyList()).join();
      prewrite(store, txnId, secondary, primary, startTs, 5, "secondary-new");

      AdbLockResolveAction action = new AdbLockResolver(store,
          lock -> AdbPrimaryLockStatus.committed(commitTs))
          .resolveExpiredLock(lock(txnId, secondary, primary, startTs, 5),
              50);

      assertEquals(AdbLockResolveAction.ROLLED_FORWARD, action);
      assertNull(store.get(VersionKey.of(secondary, false, txnId).toBytes()));
      assertNotNull(store.get(VersionKey.of(secondary, true,
          commitTs).toBytes()));

      AdbLeasedClusterCommittedVersionGcCycle cycle = gcCycle(store);
      AdbLeasedClusterCommittedVersionGcCycleResult result =
          cycle.runOnce(0, 1000);

      assertTrue(result.getSafePointResult().isLeaseAcquired());
      assertTrue(result.getSafePointResult().getAdvanceResult().get()
          .isBlockedByActiveTransaction());
      assertEquals(20, result.getSafePointResult()
          .getLeaseRecord().getSafePoint());
      assertTrue(result.isGcDispatched());
      assertEquals(1, result.getGcResult().get().getDeletedVersions());
      assertNull(store.get(VersionKey.of(secondary, true, 10).toBytes()));
      assertNotNull(store.get(VersionKey.of(secondary, true, 22).toBytes()));
      assertNotNull(store.get(VersionKey.of(secondary, true,
          commitTs).toBytes()));
    }
  }

  private static AdbLeasedClusterCommittedVersionGcCycle gcCycle(
      LdbStore store) {
    AdbSafePointLeaseStore leaseStore = new AdbSafePointLeaseStore(store);
    AdbLeasedGlobalSafePointAdvancer advancer =
        new AdbLeasedGlobalSafePointAdvancer(leaseStore,
            new AdbGlobalSafePointAdvancer(new AdbGcSafePointManager(20),
                () -> 50, () -> Collections.singletonList(25L)),
            "acceptance-gc-worker", () -> 1000, 100);
    AdbCommittedVersionGcCleaner cleaner =
        new AdbCommittedVersionGcCleaner(store,
            new AdbGcSafePointManager(0));
    AdbClusterCommittedVersionGcScheduler scheduler =
        new AdbClusterCommittedVersionGcScheduler(() -> snapshot(),
            () -> 0, new AdbLocalRegionCommittedVersionGcClient(cleaner));
    return new AdbLeasedClusterCommittedVersionGcCycle(advancer,
        scheduler);
  }

  private static AdbControlPlaneSnapshot snapshot() {
    return new AdbControlPlaneSnapshot(1,
        Collections.singletonList(region("r1", 1, 100, "node-a")));
  }

  private static RegionMetadata region(String regionId, long startRow,
      long endRow, String leaderId) {
    return new RegionMetadata(regionId,
        new KeyRange(rowKey(startRow).toBytes(), rowKey(endRow).toBytes()),
        1, new VirtualNodeMetadata("vn-" + regionId, 1, leaderId,
            Arrays.asList(
                new VirtualNodeReplica("node-a", ReplicaRole.DATA_VOTER),
                new VirtualNodeReplica("node-b", ReplicaRole.DATA_VOTER),
                new VirtualNodeReplica("witness-a",
                    ReplicaRole.WITNESS_VOTER)),
            0, 0, 0));
  }

  private static void prewrite(LdbStore store, long txnId, RowKey key,
      RowKey primaryKey, long startTs, long ttlMillis, String value)
      throws Exception {
    AdbPrewriteApplicator.prewrite(store, txnId, startTs,
        Collections.singletonList(new AdbRegionMutation(key,
            rowValue(txnId, 0, value))),
        Collections.singletonList(lock(txnId, key, primaryKey, startTs,
            ttlMillis)));
  }

  private static void putCommitted(LdbStore store, RowKey key, long commitTs,
      String value) throws Exception {
    store.put(VersionKey.of(key, true, commitTs).toBytes(),
        RowValue.encodeValue(rowValue(commitTs, commitTs, value)));
  }

  private static AdbTxnLock lock(long txnId, RowKey key, RowKey primaryKey,
      long startTs, long ttlMillis) {
    return new AdbTxnLock(txnId, key.toBytes(), primaryKey.toBytes(), startTs,
        "r1", ttlMillis);
  }

  private static RowValue rowValue(long txnId, long commitTs, String value) {
    RowValue rowValue = new RowValue();
    rowValue.txnId = txnId;
    rowValue.commitTs = commitTs;
    rowValue.payload = RowCodec.encode(ValueVarchar.get(value));
    return rowValue;
  }

  private static RowKey rowKey(long rowId) {
    return RowKey.of(TabId.of(1, 0L), rowId);
  }
}
