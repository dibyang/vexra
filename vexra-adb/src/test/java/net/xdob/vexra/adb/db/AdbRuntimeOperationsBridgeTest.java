package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.adb.ldb.LdbStore;
import net.xdob.vexra.cluster.ops.BackupRestoreMode;
import net.xdob.vexra.cluster.ops.BackupRestorePlan;
import net.xdob.vexra.cluster.ops.ClusterHealthStatus;
import net.xdob.vexra.cluster.ops.ClusterOperationsSnapshot;
import net.xdob.vexra.cluster.region.KeyRange;
import net.xdob.vexra.cluster.region.RegionMetadata;
import net.xdob.vexra.ha.ReplicaRole;
import net.xdob.vexra.ha.VirtualNodeMetadata;
import net.xdob.vexra.ha.VirtualNodeReplica;
import org.h2.value.ValueVarchar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADB runtime 运维桥接测试。
 *
 * <p>测试覆盖 ADB-Runtime-11 的最小运维闭环：分布式模式默认关闭与安全启用约束、
 * operations system row/metrics，以及本地 FULL backup/restore drill。</p>
 */
class AdbRuntimeOperationsBridgeTest {
  @TempDir
  File tempDir;

  /**
   * 验证分布式模式默认关闭，显式开启时必须同时开启 TLS 和最小权限。
   */
  @Test
  void shouldDisableDistributedModeByDefaultAndRequireSecurityFlags() {
    AdbDistributedRuntimeOptions defaults =
        AdbDistributedRuntimeOptions.singleNodeDefault();

    assertFalse(defaults.isDistributedEnabled());
    assertFalse(defaults.isTlsEnabled());
    assertFalse(defaults.isLeastPrivilegeEnabled());
    assertThrows(IllegalArgumentException.class,
        () -> new AdbDistributedRuntimeOptions(true, true, false));
    assertThrows(IllegalArgumentException.class,
        () -> new AdbDistributedRuntimeOptions(true, false, true));

    AdbDistributedRuntimeOptions enabled =
        new AdbDistributedRuntimeOptions(true, true, true);
    assertTrue(enabled.isDistributedEnabled());
  }

  /**
   * 验证 operations bridge 可以输出 system row 和 metrics。
   */
  @Test
  void shouldExposeOperationsSnapshotRowsAndMetrics() throws Exception {
    try (LdbStore store = new LdbStore(new File(tempDir, "ops-store")
        .getAbsolutePath())) {
      InMemoryAdbControlPlaneClient controlPlane =
          new InMemoryAdbControlPlaneClient(Arrays.asList(
              region("r1", new KeyRange(new byte[0], rowKey(50).toBytes()),
                  "node-a", 1, 1),
              region("r2", new KeyRange(rowKey(50).toBytes(), new byte[0]),
                  "", 1, 1)), 100);
      AdbRuntimeOperationsBridge bridge =
          new AdbRuntimeOperationsBridge(store, controlPlane, "0.4.0-test");

      ClusterOperationsSnapshot snapshot = bridge.collectSnapshot(true);
      Map<String, String> row = bridge.systemTableRow(true);
      Map<String, Number> metrics = bridge.metrics(true);

      assertEquals(ClusterHealthStatus.DEGRADED, snapshot.getHealthStatus());
      assertEquals(2, snapshot.getRegionCount());
      assertEquals(1, snapshot.getWritableRegionCount());
      assertEquals("DEGRADED", row.get("health_status"));
      assertEquals("0.4.0-test", row.get("cluster_version"));
      assertEquals(1, metrics.get("vexra_cluster_ddl_running"));
      assertEquals(1, metrics.get("vexra_cluster_unavailable_region_count"));
    }
  }

  /**
   * 验证 FULL backup/restore drill 可以恢复 checkpoint 中的数据。
   */
  @Test
  void shouldRunFullBackupAndRestoreDrill() throws Exception {
    File storeDir = new File(tempDir, "backup-store");
    File backupDir = new File(tempDir, "backup-dir");
    RowKey key = rowKey(7);
    try (LdbStore store = new LdbStore(storeDir.getAbsolutePath())) {
      TxnManager manager = new TxnManager(store);
      putCommitted(manager, key, "before-backup");

      AdbRuntimeOperationsBridge bridge =
          new AdbRuntimeOperationsBridge(store,
              new InMemoryAdbControlPlaneClient(Collections.singletonList(
                  region("r1", new KeyRange(new byte[0], new byte[0]),
                      "node-a", 1, 1)), 100), "0.4.0-test");
      BackupRestorePlan plan = new BackupRestorePlan("backup-1",
          BackupRestoreMode.FULL, Collections.singletonList("r1"),
          backupDir.getAbsolutePath(), manager.lastCommitTs());

      bridge.backup(plan);
      putCommitted(manager, key, "after-backup");
      bridge.restore(plan);

      RowValue value = manager.getVisible(manager.beginTransaction(), key);
      assertNotNull(value);
      assertEquals("before-backup", RowCodec.decode(value.payload).getString());
    }
  }

  /**
   * 验证当前 runtime drill 不接受增量备份，避免误以为已具备完整 PITR。
   */
  @Test
  void shouldRejectUnsupportedBackupMode() throws Exception {
    try (LdbStore store = new LdbStore(new File(tempDir, "reject-store")
        .getAbsolutePath())) {
      AdbRuntimeOperationsBridge bridge =
          new AdbRuntimeOperationsBridge(store,
              new InMemoryAdbControlPlaneClient(Collections.singletonList(
                  region("r1", new KeyRange(new byte[0], new byte[0]),
                      "node-a", 1, 1)), 100), "0.4.0-test");
      BackupRestorePlan plan = new BackupRestorePlan("backup-2",
          BackupRestoreMode.INCREMENTAL, Collections.singletonList("r1"),
          new File(tempDir, "unsupported").getAbsolutePath(), 1);

      assertThrows(java.io.IOException.class, () -> bridge.backup(plan));
    }
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
