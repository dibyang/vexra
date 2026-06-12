package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.adb.ldb.LdbStore;
import net.xdob.vexra.cluster.region.KeyRange;
import net.xdob.vexra.cluster.region.RegionMetadata;
import net.xdob.vexra.cluster.region.RegionSnapshotInstallPlan;
import net.xdob.vexra.ha.ReplicaRole;
import net.xdob.vexra.ha.VirtualNodeMetadata;
import net.xdob.vexra.ha.VirtualNodeReplica;
import org.h2.value.ValueVarchar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADB region topology 与 snapshot install 运行时测试。
 *
 * <p>测试覆盖 ADB-Runtime-08 的最小闭环：split/merge 发布 route snapshot 并推进
 * route epoch，以及 checkpoint snapshot 安装到新的 ADB store 后仍可读取已提交数据。</p>
 */
class AdbRegionTopologyManagerTest {
  @TempDir
  File tempDir;

  /**
   * 验证 split/merge 会推进 route epoch，并让新 router 命中正确 region。
   */
  @Test
  void shouldPublishSplitAndMergeRouteSnapshots() {
    RowKey splitKey = rowKey(50);
    InMemoryAdbControlPlaneClient controlPlane =
        new InMemoryAdbControlPlaneClient(Collections.singletonList(
            region("r1", new KeyRange(new byte[0], new byte[0]), "node-a",
                1, 1)), 100);
    AdbRegionTopologyManager manager =
        new AdbRegionTopologyManager(controlPlane);

    AdbControlPlaneSnapshot split = manager.splitRegion("r1",
        splitKey.toBytes(), "r1-left", "r1-right");

    assertEquals(2, split.getRouteEpoch());
    assertEquals("r1-left", split.getRouter().route(rowKey(1).toBytes())
        .getRegionId());
    assertEquals("r1-right", split.getRouter().route(rowKey(100).toBytes())
        .getRegionId());

    AdbControlPlaneSnapshot merged = manager.mergeAdjacentRegions("r1-left",
        "r1-right", "r1-merged");

    assertEquals(3, merged.getRouteEpoch());
    assertEquals("r1-merged", merged.getRouter().route(rowKey(1).toBytes())
        .getRegionId());
    assertEquals("r1-merged", merged.getRouter().route(rowKey(100).toBytes())
        .getRegionId());
  }

  /**
   * 验证 split key 必须位于父 region 内部，不能等于边界。
   */
  @Test
  void shouldRejectSplitKeyOnRegionBoundary() {
    RowKey splitKey = rowKey(50);
    InMemoryAdbControlPlaneClient controlPlane =
        new InMemoryAdbControlPlaneClient(Collections.singletonList(
            region("r1", new KeyRange(new byte[0], splitKey.toBytes()),
                "node-a", 1, 1)), 100);
    AdbRegionTopologyManager manager =
        new AdbRegionTopologyManager(controlPlane);

    assertThrows(IllegalArgumentException.class,
        () -> manager.splitRegion("r1", splitKey.toBytes(), "left", "right"));
  }

  /**
   * 验证 checkpoint snapshot 可以安装到目标 store，安装后已提交行可读。
   */
  @Test
  void shouldInstallSnapshotIntoTargetStoreAndReadCommittedData()
      throws Exception {
    File sourceDir = new File(tempDir, "source");
    File targetDir = new File(tempDir, "target");
    File snapshotDir = new File(tempDir, "snapshot");
    RowKey key = rowKey(7);
    try (LdbStore source = new LdbStore(sourceDir.getAbsolutePath());
         LdbStore target = new LdbStore(targetDir.getAbsolutePath())) {
      TxnManager sourceManager = new TxnManager(source);
      Transaction2 writeTxn = sourceManager.beginTransaction();
      sourceManager.put(writeTxn, key, rowValue("snapshot-value"));
      sourceManager.commit(writeTxn);
      source.checkpoint(snapshotDir.getAbsolutePath());

      AdbRegionSnapshotInstaller installer =
          new AdbRegionSnapshotInstaller(target, "node-b");
      installer.install(new RegionSnapshotInstallPlan("r1", 1, 10,
          Collections.singletonList("node-b")), snapshotDir.getAbsolutePath());

      TxnManager targetManager = new TxnManager(target);
      RowValue value = targetManager.getVisible(
          targetManager.beginTransaction(), key);
      assertNotNull(value);
      assertEquals("snapshot-value", RowCodec.decode(value.payload).getString());
    }
  }

  /**
   * 验证非目标副本不能安装该 region snapshot。
   */
  @Test
  void shouldRejectSnapshotInstallForNonTargetReplica() throws Exception {
    File sourceDir = new File(tempDir, "source-reject");
    try (LdbStore store = new LdbStore(sourceDir.getAbsolutePath())) {
      AdbRegionSnapshotInstaller installer =
          new AdbRegionSnapshotInstaller(store, "node-x");
      assertThrows(java.io.IOException.class,
          () -> installer.install(new RegionSnapshotInstallPlan("r1", 1, 10,
              Collections.singletonList("node-b")), sourceDir.getAbsolutePath()));
    }
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
}
