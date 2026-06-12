package net.xdob.vexra.adb.db;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ADB GC safe point manager 测试。
 *
 * <p>验证 safe point 只能单调推进，并且不能推进到活跃长事务的 startTs 或之后。
 * 这是后续真实 GC worker 删除历史版本前的最小安全门。</p>
 */
class AdbGcSafePointManagerTest {
  /**
   * 验证 safe point 可以在无活跃事务时前进，并驱动可回收判断。
   */
  @Test
  void shouldAdvanceSafePointAndCheckCollectableVersions() {
    AdbGcSafePointManager manager = new AdbGcSafePointManager(0);

    assertEquals(10, manager.advanceTo(10, Collections.emptyList()));

    assertTrue(manager.canCollect(9));
    assertFalse(manager.canCollect(10));
  }

  /**
   * 验证 safe point 不能回退。
   */
  @Test
  void shouldRejectBackwardSafePoint() {
    AdbGcSafePointManager manager = new AdbGcSafePointManager(10);

    assertThrows(IllegalArgumentException.class,
        () -> manager.advanceTo(9, Collections.emptyList()));
  }

  /**
   * 验证活跃长事务会阻止 safe point 推进到不安全位置。
   */
  @Test
  void shouldProtectActiveLongTransactions() {
    AdbGcSafePointManager manager = new AdbGcSafePointManager(3);

    assertThrows(IllegalStateException.class,
        () -> manager.advanceTo(8, Arrays.asList(8L, 12L)));
    assertEquals(3, manager.getSafePoint());
  }
}
