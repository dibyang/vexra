package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.adb.ldb.LdbStore;
import net.xdob.vexra.cluster.region.KeyRange;
import net.xdob.vexra.cluster.region.RegionMetadata;
import net.xdob.vexra.cluster.region.RegionRouter;
import net.xdob.vexra.cluster.sql.DistributedPlan;
import net.xdob.vexra.ha.ReplicaRole;
import net.xdob.vexra.ha.VirtualNodeMetadata;
import net.xdob.vexra.ha.VirtualNodeReplica;
import org.h2.value.ValueVarchar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADB 分布式计划 adapter 测试。
 *
 * <p>测试覆盖 ADB-Runtime-09 的最小交付：把 ADB table row scan 转换为按 region
 * 切分的 `DistributedPlan`，输出 explain 诊断文本，并通过本地 region scan bridge
 * 执行基础 count pushdown。</p>
 */
class AdbDistributedPlanAdapterTest {
  @TempDir
  File tempDir;

  /**
   * 验证 table row scan 会按 region range 生成多个 scan task。
   */
  @Test
  void shouldBuildRegionSplitTableScanPlanAndExplainIt() {
    AdbDistributedPlanAdapter adapter =
        new AdbDistributedPlanAdapter(router(rowKey(50)));

    DistributedPlan plan = adapter.tableRowScan(TabId.of(1, 0L),
        1L, 100L, Collections.singletonList("payload"),
        Collections.singletonList("row_id >= 1"), 10, 42, false);

    assertFalse(plan.isCountOnly());
    assertEquals(2, plan.getTasks().size());
    assertEquals("r1", plan.getTasks().get(0).getRegionId());
    assertEquals("r2", plan.getTasks().get(1).getRegionId());
    assertEquals("payload", plan.getTasks().get(0).getProjections().get(0));
    assertEquals("row_id >= 1", plan.getTasks().get(0).getFilters().get(0));
    assertTrue(plan.getTasks().get(0).getKeyRange()
        .contains(rowKey(1).toBytes()));
    assertFalse(plan.getTasks().get(0).getKeyRange()
        .contains(rowKey(100).toBytes()));

    List<String> explain = adapter.explain(plan);
    assertEquals(2, explain.size());
    assertTrue(explain.get(0).contains("REGION_SCAN region=r1"));
    assertTrue(explain.get(0).contains("countOnly=false"));
    assertTrue(explain.get(0).contains("readTs=42"));
  }

  /**
   * 验证 adapter 生成的 count-only 计划可以通过本地 bridge 执行。
   */
  @Test
  void shouldExecuteAdapterPlanThroughLocalRegionScanBridge() throws Exception {
    try (LdbStore store = new LdbStore(new File(tempDir, "plan-store")
        .getAbsolutePath())) {
      TxnManager manager = new TxnManager(store);
      putCommitted(manager, rowKey(1), "left");
      putCommitted(manager, rowKey(100), "right");

      Transaction2 readTxn = manager.beginTransaction();
      AdbDistributedPlanAdapter adapter =
          new AdbDistributedPlanAdapter(router(rowKey(50)));
      DistributedPlan plan = adapter.tableRowScan(TabId.of(1, 0L),
          1L, 100L, null, null, 0, readTxn.getStartTs(), true);
      AdbDistributedRegionScanExecutor executor =
          new AdbDistributedRegionScanExecutor(new AdbLocalRegionScanClient(
              new AdbLocalRegionScanExecutor(store)));

      assertEquals(2, executor.executeCount(readTxn, plan, 5000));
    }
  }

  /**
   * 验证非法 rowId 范围会被拒绝，避免生成空洞计划。
   */
  @Test
  void shouldRejectInvalidRowIdRange() {
    AdbDistributedPlanAdapter adapter =
        new AdbDistributedPlanAdapter(router(rowKey(50)));

    assertThrows(IllegalArgumentException.class,
        () -> adapter.tableRowScan(TabId.of(1, 0L), 100L, 1L,
            null, null, 0, 1, false));
  }

  private static void putCommitted(TxnManager manager, RowKey key, String value)
      throws Exception {
    Transaction2 txn = manager.beginTransaction();
    manager.put(txn, key, rowValue(value));
    manager.commit(txn);
  }

  private static RowKey rowKey(long rowId) {
    return RowKey.of(TabId.of(1, 0L), rowId);
  }

  private static RowValue rowValue(String value) {
    RowValue rowValue = new RowValue();
    rowValue.payload = RowCodec.encode(ValueVarchar.get(value));
    return rowValue;
  }

  private static RegionRouter router(RowKey splitKey) {
    return new RegionRouter(Arrays.asList(
        region("r1", new KeyRange(new byte[0], splitKey.toBytes()),
            "node-a", 1, 1),
        region("r2", new KeyRange(splitKey.toBytes(), new byte[0]),
            "node-b", 1, 1)));
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
}
