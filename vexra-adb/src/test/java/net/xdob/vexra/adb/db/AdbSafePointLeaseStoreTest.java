package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.ldb.LdbStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADB safe point lease store 测试。
 *
 * <p>使用真实 LDB store 覆盖 safe point 持久化、本地 lease 抢占、续租、释放和
 * 单调推进约束，为后续 PD/etcd 级租约替换提供稳定行为基线。</p>
 */
class AdbSafePointLeaseStoreTest {
  @TempDir
  private Path tempDir;

  /**
   * 验证未初始化时返回空记录，获取 lease 后可被新的 store adapter 读回。
   */
  @Test
  void shouldAcquireAndPersistLeaseRecord() throws Exception {
    try (LdbStore store = new LdbStore(
        tempDir.resolve("safe-point-acquire").toString())) {
      AdbSafePointLeaseStore leaseStore = new AdbSafePointLeaseStore(store);

      AdbSafePointLeaseRecord empty = leaseStore.read();
      assertEquals(0, empty.getSafePoint());
      assertEquals("", empty.getOwnerId());

      Optional<AdbSafePointLeaseRecord> acquired =
          leaseStore.tryAcquire("gc-worker-a", 100, 50);

      assertTrue(acquired.isPresent());
      assertEquals("gc-worker-a", acquired.get().getOwnerId());
      assertEquals(150, acquired.get().getLeaseUntilMillis());

      AdbSafePointLeaseRecord persisted =
          new AdbSafePointLeaseStore(store).read();
      assertEquals("gc-worker-a", persisted.getOwnerId());
      assertEquals(150, persisted.getLeaseUntilMillis());
    }
  }

  /**
   * 验证未过期 lease 会拒绝其他 owner，同 owner 可以续租。
   */
  @Test
  void shouldRejectCompetitorAndRenewSameOwner() throws Exception {
    try (LdbStore store = new LdbStore(
        tempDir.resolve("safe-point-renew").toString())) {
      AdbSafePointLeaseStore leaseStore = new AdbSafePointLeaseStore(store);
      assertTrue(leaseStore.tryAcquire("owner-a", 100, 50).isPresent());

      assertFalse(leaseStore.tryAcquire("owner-b", 120, 50).isPresent());

      Optional<AdbSafePointLeaseRecord> renewed =
          leaseStore.tryAcquire("owner-a", 130, 70);
      assertTrue(renewed.isPresent());
      assertEquals("owner-a", renewed.get().getOwnerId());
      assertEquals(200, renewed.get().getLeaseUntilMillis());
    }
  }

  /**
   * 验证 lease 到期后其他 owner 可以接管。
   */
  @Test
  void shouldAllowTakeoverAfterLeaseExpired() throws Exception {
    try (LdbStore store = new LdbStore(
        tempDir.resolve("safe-point-takeover").toString())) {
      AdbSafePointLeaseStore leaseStore = new AdbSafePointLeaseStore(store);
      assertTrue(leaseStore.tryAcquire("owner-a", 100, 50).isPresent());

      Optional<AdbSafePointLeaseRecord> takeover =
          leaseStore.tryAcquire("owner-b", 151, 40);

      assertTrue(takeover.isPresent());
      assertEquals("owner-b", takeover.get().getOwnerId());
      assertEquals(191, takeover.get().getLeaseUntilMillis());
    }
  }

  /**
   * 验证只有 lease 持有者可以单调推进 safe point。
   */
  @Test
  void shouldAdvanceSafePointOnlyWhenLeaseHeld() throws Exception {
    try (LdbStore store = new LdbStore(
        tempDir.resolve("safe-point-advance").toString())) {
      AdbSafePointLeaseStore leaseStore = new AdbSafePointLeaseStore(store);
      assertTrue(leaseStore.tryAcquire("owner-a", 100, 50).isPresent());

      AdbSafePointLeaseRecord advanced =
          leaseStore.advanceSafePoint("owner-a", 30, 120);

      assertEquals(30, advanced.getSafePoint());
      assertEquals(30, leaseStore.read().getSafePoint());
      assertThrows(SQLException.class,
          () -> leaseStore.advanceSafePoint("owner-b", 40, 121));
      assertThrows(IllegalArgumentException.class,
          () -> leaseStore.advanceSafePoint("owner-a", 20, 122));
      assertThrows(SQLException.class,
          () -> leaseStore.advanceSafePoint("owner-a", 40, 151));
    }
  }

  /**
   * 验证 release 只允许当前未过期持有者释放，并保留 safe point。
   */
  @Test
  void shouldReleaseLeaseButKeepSafePoint() throws Exception {
    try (LdbStore store = new LdbStore(
        tempDir.resolve("safe-point-release").toString())) {
      AdbSafePointLeaseStore leaseStore = new AdbSafePointLeaseStore(store);
      assertTrue(leaseStore.tryAcquire("owner-a", 100, 50).isPresent());
      leaseStore.advanceSafePoint("owner-a", 30, 120);

      assertFalse(leaseStore.release("owner-b", 121));
      assertTrue(leaseStore.release("owner-a", 122));

      AdbSafePointLeaseRecord released = leaseStore.read();
      assertEquals(30, released.getSafePoint());
      assertEquals("", released.getOwnerId());
      assertEquals(0, released.getLeaseUntilMillis());
    }
  }
}
