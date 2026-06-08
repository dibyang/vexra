package net.xdob.vexra.cluster.ops;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 集群运维和发布模型回归测试。
 *
 * <p>测试覆盖 ADB-Cluster-07 的最小公共模型：健康快照、metrics/system row、
 * 滚动升级顺序和备份恢复计划。</p>
 */
class ClusterOperationsModelTest {
  /**
   * 验证运维快照可输出系统表行和 metrics。
   */
  @Test
  void shouldExposeOperationsSnapshotRowsAndMetrics() {
    ClusterOperationsSnapshot snapshot = new ClusterOperationsSnapshot(
        ClusterHealthStatus.DEGRADED, 3, 2, 1,
        true, false, "0.4.0");

    Map<String, String> row = snapshot.toSystemTableRow();
    Map<String, Number> metrics = snapshot.toMetrics();

    assertEquals("DEGRADED", row.get("health_status"));
    assertEquals("0.4.0", row.get("cluster_version"));
    assertEquals(3, metrics.get("vexra_cluster_region_count"));
    assertEquals(1, metrics.get("vexra_cluster_unavailable_region_count"));
    assertEquals(1, metrics.get("vexra_cluster_ddl_running"));
  }

  /**
   * 验证滚动升级计划按节点顺序推进并能判断完成。
   */
  @Test
  void shouldAdvanceRollingUpgradePlan() {
    RollingUpgradePlan plan = new RollingUpgradePlan("0.4.0",
        Arrays.asList("node-a", "node-b"), null);

    assertEquals("node-a", plan.nextNode());
    RollingUpgradePlan afterA = plan.markUpgraded("node-a");
    assertEquals("node-b", afterA.nextNode());
    RollingUpgradePlan afterB = afterA.markUpgraded("node-b");
    assertTrue(afterB.isComplete());
    assertEquals("", afterB.nextNode());
  }

  /**
   * 验证备份恢复计划保存模式、region、位置和 checkpoint。
   */
  @Test
  void shouldDescribeBackupRestorePlan() {
    BackupRestorePlan plan = new BackupRestorePlan("backup-1",
        BackupRestoreMode.POINT_IN_TIME, Arrays.asList("r1", "r2"),
        "s3://bucket/vexra", 100);

    assertEquals("backup-1", plan.getPlanId());
    assertEquals(BackupRestoreMode.POINT_IN_TIME, plan.getMode());
    assertEquals(2, plan.getRegionIds().size());
    assertEquals("s3://bucket/vexra", plan.getLocation());
    assertEquals(100, plan.getCheckpointTs());
  }

  /**
   * 验证不合法的 region 计数会被拒绝，避免观测数据误导发布判断。
   */
  @Test
  void shouldRejectInvalidOperationsSnapshotCounts() {
    assertThrows(IllegalArgumentException.class,
        () -> new ClusterOperationsSnapshot(ClusterHealthStatus.HEALTHY,
            1, 2, 0, false, false, "0.4.0"));
  }
}
