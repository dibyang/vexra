package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.ldb.LdbStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADB 带租约的全局 safe point 推进器测试。
 *
 * <p>使用真实 LDB store 覆盖本地 safe point lease 与进程内 safe point 推进器的
 * 组合语义，确保未持有租约的 worker 不会推进，持有者可以续租、持久化推进结果，
 * 并在长事务阻塞或本地状态落后时保持 safe point 单调。</p>
 */
class AdbLeasedGlobalSafePointAdvancerTest {
  @TempDir
  private Path tempDir;

  /**
   * 验证拿到 lease 的 worker 会推进 safe point 并持久化 lease 记录。
   */
  @Test
  void shouldAcquireLeaseAdvanceAndPersistSafePoint() throws Exception {
    try (LdbStore store = new LdbStore(
        tempDir.resolve("leased-advance").toString())) {
      AdbSafePointLeaseStore leaseStore = new AdbSafePointLeaseStore(store);
      AdbGcSafePointManager manager = new AdbGcSafePointManager(0);
      AdbLeasedGlobalSafePointAdvancer advancer =
          new AdbLeasedGlobalSafePointAdvancer(leaseStore,
              new AdbGlobalSafePointAdvancer(manager, () -> 30,
                  Collections::emptyList),
              "owner-a", () -> 100, 50);

      AdbLeasedGlobalSafePointAdvanceResult result =
          advancer.advanceOnce();

      assertTrue(result.isLeaseAcquired());
      assertTrue(result.getAdvanceResult().isPresent());
      assertTrue(result.getAdvanceResult().get().isAdvanced());
      assertEquals(30, result.getAdvanceResult().get().getSafePoint());
      assertEquals(30, result.getLeaseRecord().getSafePoint());
      assertEquals("owner-a", result.getLeaseRecord().getOwnerId());
      assertEquals(150, result.getLeaseRecord().getLeaseUntilMillis());
      assertEquals(30, leaseStore.read().getSafePoint());
    }
  }

  /**
   * 验证 lease 被其他 owner 持有时，本 worker 会跳过且不调用底层推进器。
   */
  @Test
  void shouldSkipWhenLeaseHeldByOtherOwner() throws Exception {
    try (LdbStore store = new LdbStore(
        tempDir.resolve("leased-skip").toString())) {
      AdbSafePointLeaseStore leaseStore = new AdbSafePointLeaseStore(store);
      assertTrue(leaseStore.tryAcquire("owner-b", 100, 100).isPresent());
      AtomicInteger candidateCalls = new AtomicInteger();
      AdbLeasedGlobalSafePointAdvancer advancer =
          new AdbLeasedGlobalSafePointAdvancer(leaseStore,
              new AdbGlobalSafePointAdvancer(new AdbGcSafePointManager(0),
                  () -> {
                    candidateCalls.incrementAndGet();
                    return 30;
                  }, Collections::emptyList),
              "owner-a", () -> 120, 50);

      AdbLeasedGlobalSafePointAdvanceResult result =
          advancer.advanceOnce();

      assertFalse(result.isLeaseAcquired());
      assertFalse(result.getAdvanceResult().isPresent());
      assertEquals(0, candidateCalls.get());
      assertEquals("owner-b", result.getLeaseRecord().getOwnerId());
      assertEquals(200, result.getLeaseRecord().getLeaseUntilMillis());
      assertEquals(0, leaseStore.read().getSafePoint());
    }
  }

  /**
   * 验证同 owner 续租后即使被长事务阻塞，也会保留当前 safe point 和新租约。
   */
  @Test
  void shouldRenewLeaseAndPersistBlockedResult() throws Exception {
    try (LdbStore store = new LdbStore(
        tempDir.resolve("leased-blocked").toString())) {
      AdbSafePointLeaseStore leaseStore = new AdbSafePointLeaseStore(store);
      assertTrue(leaseStore.tryAcquire("owner-a", 100, 20).isPresent());
      leaseStore.advanceSafePoint("owner-a", 10, 110);
      AdbLeasedGlobalSafePointAdvancer advancer =
          new AdbLeasedGlobalSafePointAdvancer(leaseStore,
              new AdbGlobalSafePointAdvancer(new AdbGcSafePointManager(10),
                  () -> 50, () -> Collections.singletonList(20L)),
              "owner-a", () -> 115, 50);

      AdbLeasedGlobalSafePointAdvanceResult result =
          advancer.advanceOnce();

      assertTrue(result.isLeaseAcquired());
      assertTrue(result.getAdvanceResult().isPresent());
      assertTrue(result.getAdvanceResult().get()
          .isBlockedByActiveTransaction());
      assertEquals(10, result.getLeaseRecord().getSafePoint());
      assertEquals("owner-a", result.getLeaseRecord().getOwnerId());
      assertEquals(165, result.getLeaseRecord().getLeaseUntilMillis());
      assertEquals(10, leaseStore.read().getSafePoint());
    }
  }

  /**
   * 验证持久化 safe point 已领先时，本轮不会把记录回退到进程内旧值。
   */
  @Test
  void shouldKeepPersistedSafePointMonotonicWhenDelegateIsBehind()
      throws Exception {
    try (LdbStore store = new LdbStore(
        tempDir.resolve("leased-monotonic").toString())) {
      AdbSafePointLeaseStore leaseStore = new AdbSafePointLeaseStore(store);
      assertTrue(leaseStore.tryAcquire("owner-a", 100, 50).isPresent());
      leaseStore.advanceSafePoint("owner-a", 40, 120);
      AdbLeasedGlobalSafePointAdvancer advancer =
          new AdbLeasedGlobalSafePointAdvancer(leaseStore,
              new AdbGlobalSafePointAdvancer(new AdbGcSafePointManager(10),
                  () -> 20, Collections::emptyList),
              "owner-a", () -> 125, 50);

      AdbLeasedGlobalSafePointAdvanceResult result =
          advancer.advanceOnce();

      assertTrue(result.isLeaseAcquired());
      assertEquals(20, result.getAdvanceResult().get().getSafePoint());
      assertEquals(40, result.getLeaseRecord().getSafePoint());
      assertEquals(175, result.getLeaseRecord().getLeaseUntilMillis());
      assertEquals(40, leaseStore.read().getSafePoint());
    }
  }
}
