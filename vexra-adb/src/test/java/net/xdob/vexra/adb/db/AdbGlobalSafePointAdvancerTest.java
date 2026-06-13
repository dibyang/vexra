package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.ldb.LdbStore;
import net.xdob.vexra.cluster.region.KeyRange;
import net.xdob.vexra.cluster.region.RegionMetadata;
import net.xdob.vexra.ha.ReplicaRole;
import net.xdob.vexra.ha.VirtualNodeMetadata;
import net.xdob.vexra.ha.VirtualNodeReplica;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADB 全局 safe point 推进器测试。
 *
 * <p>覆盖进程内活跃事务 startTs 快照、safe point 单调推进、长事务保护以及
 * 控制面 TSO 保留窗口计算，确保 GC 不会越过仍在运行的快照读。</p>
 */
class AdbGlobalSafePointAdvancerTest {
  @TempDir
  private Path tempDir;

  /**
   * 验证 TxnManager 会记录 begin 后的活跃事务，并在 commit/rollback 成功后移除。
   */
  @Test
  void shouldExposeActiveTransactionStartTimestamps() throws Exception {
    try (LdbStore store = new LdbStore(
        tempDir.resolve("active-txns").toString())) {
      TxnManager manager = new TxnManager(store);
      manager.setTimestampProvider(new SequenceTimestampProvider(100));

      Transaction2 first = manager.beginTransaction();
      Transaction2 second = manager.beginTransaction();

      assertEquals(Arrays.asList(101L, 102L),
          manager.activeStartTsSnapshot());

      manager.rollback(first);
      assertEquals(Collections.singletonList(102L),
          manager.activeStartTsSnapshot());

      manager.commit(second);
      assertTrue(manager.activeStartTsSnapshot().isEmpty());
    }
  }

  /**
   * 验证无活跃事务时 safe point 会推进到候选值。
   */
  @Test
  void shouldAdvanceSafePointWhenNoActiveTransaction() {
    AdbGcSafePointManager manager = new AdbGcSafePointManager(10);
    AdbGlobalSafePointAdvancer advancer =
        new AdbGlobalSafePointAdvancer(manager, () -> 30,
            Collections::emptyList);

    AdbGlobalSafePointAdvanceResult result = advancer.advanceOnce();

    assertTrue(result.isAdvanced());
    assertFalse(result.isBlockedByActiveTransaction());
    assertEquals(10, result.getPreviousSafePoint());
    assertEquals(30, result.getCandidateSafePoint());
    assertEquals(30, result.getSafePoint());
    assertEquals(30, manager.getSafePoint());
  }

  /**
   * 验证活跃长事务会阻止 safe point 覆盖其 startTs。
   */
  @Test
  void shouldBlockWhenCandidateReachesActiveTransaction() {
    AdbGcSafePointManager manager = new AdbGcSafePointManager(10);
    AdbGlobalSafePointAdvancer advancer =
        new AdbGlobalSafePointAdvancer(manager, () -> 50,
            () -> Collections.singletonList(20L));

    AdbGlobalSafePointAdvanceResult result = advancer.advanceOnce();

    assertFalse(result.isAdvanced());
    assertTrue(result.isBlockedByActiveTransaction());
    assertEquals(10, result.getSafePoint());
    assertEquals(Collections.singletonList(20L), result.getActiveStartTs());
    assertEquals(10, manager.getSafePoint());
  }

  /**
   * 验证候选 safe point 小于当前值时不会回退。
   */
  @Test
  void shouldNotMoveSafePointBackward() {
    AdbGcSafePointManager manager = new AdbGcSafePointManager(40);
    AdbGlobalSafePointAdvancer advancer =
        new AdbGlobalSafePointAdvancer(manager, () -> 30,
            Collections::emptyList);

    AdbGlobalSafePointAdvanceResult result = advancer.advanceOnce();

    assertFalse(result.isAdvanced());
    assertFalse(result.isBlockedByActiveTransaction());
    assertEquals(30, result.getCandidateSafePoint());
    assertEquals(40, result.getSafePoint());
    assertEquals(40, manager.getSafePoint());
  }

  /**
   * 验证控制面 TSO 构造器会按 GC 保留窗口计算候选 safe point。
   */
  @Test
  void shouldComputeCandidateFromControlPlaneTsoAndGcLifeTime() {
    AdbGcSafePointManager manager = new AdbGcSafePointManager(0);
    InMemoryAdbControlPlaneClient controlPlane =
        new InMemoryAdbControlPlaneClient(
            Collections.singletonList(region("r1")), 100);
    AdbGlobalSafePointAdvancer advancer =
        new AdbGlobalSafePointAdvancer(manager, controlPlane, 10,
            Collections::emptyList);

    AdbGlobalSafePointAdvanceResult result = advancer.advanceOnce();

    assertEquals(91, result.getCandidateSafePoint());
    assertEquals(91, result.getSafePoint());
  }

  /**
   * 验证负数候选值会被拒绝，避免错误配置污染 safe point。
   */
  @Test
  void shouldRejectNegativeCandidateSafePoint() {
    AdbGlobalSafePointAdvancer advancer =
        new AdbGlobalSafePointAdvancer(new AdbGcSafePointManager(0),
            () -> -1, Collections::emptyList);

    assertThrows(IllegalArgumentException.class, advancer::advanceOnce);
  }

  private static RegionMetadata region(String regionId) {
    return new RegionMetadata(regionId, new KeyRange(new byte[0],
        new byte[0]), 1, new VirtualNodeMetadata("vn-" + regionId, 1,
        "node-a", Arrays.asList(
            new VirtualNodeReplica("node-a", ReplicaRole.DATA_VOTER),
            new VirtualNodeReplica("node-b", ReplicaRole.DATA_VOTER),
            new VirtualNodeReplica("witness-a", ReplicaRole.WITNESS_VOTER)),
        0, 0, 0));
  }

  private static final class SequenceTimestampProvider
      implements AdbTimestampProvider {
    private final AtomicLong timestamp;

    private SequenceTimestampProvider(long initialTimestamp) {
      this.timestamp = new AtomicLong(initialTimestamp);
    }

    @Override
    public long nextStartTimestamp() {
      return timestamp.incrementAndGet();
    }

    @Override
    public long nextCommitTimestamp() {
      return timestamp.incrementAndGet();
    }

    @Override
    public long lastTimestamp() {
      return timestamp.get();
    }
  }
}
