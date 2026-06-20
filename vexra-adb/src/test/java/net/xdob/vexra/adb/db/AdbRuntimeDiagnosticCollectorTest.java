package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.ldb.LdbStore;
import net.xdob.vexra.cluster.ops.ClusterHealthStatus;
import net.xdob.vexra.cluster.region.KeyRange;
import net.xdob.vexra.cluster.region.RegionMetadata;
import net.xdob.vexra.ha.ReplicaRole;
import net.xdob.vexra.ha.VirtualNodeMetadata;
import net.xdob.vexra.ha.VirtualNodeReplica;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ADB runtime 诊断采集器测试。
 *
 * <p>测试使用真实 LDB store 和内存控制面，证明 system row 与 metrics 可以进入
 * 诊断包模型，而不是只停留在独立的运维 bridge API 上。</p>
 */
class AdbRuntimeDiagnosticCollectorTest {
  @TempDir
  File tempDir;

  /**
   * 验证 runtime system row 和 metrics 被转换为诊断包字段。
   *
   * @throws Exception store 初始化失败时抛出
   */
  @Test
  void shouldCollectRuntimeOperationsAndMetrics() throws Exception {
    try (LdbStore store = new LdbStore(new File(tempDir, "runtime-diag-store")
        .getAbsolutePath())) {
      AdbRuntimeOperationsBridge bridge = new AdbRuntimeOperationsBridge(store,
          new InMemoryAdbControlPlaneClient(Arrays.asList(
              region("r1", new byte[0], new byte[] {50}, "node-a"),
              region("r2", new byte[] {50}, new byte[0], "")), 100),
          "0.6.0-test");

      AdbRuntimeDiagnosticCollector.Snapshot snapshot =
          new AdbRuntimeDiagnosticCollector(bridge).collect(true);

      assertEquals(ClusterHealthStatus.DEGRADED.name(),
          snapshot.getOperations().get("runtime.health_status"));
      assertEquals("0.6.0-test",
          snapshot.getOperations().get("runtime.cluster_version"));
      assertEquals(1,
          snapshot.getMetrics().get("vexra_cluster_ddl_running"));
      assertEquals(1,
          snapshot.getMetrics().get(
              "vexra_cluster_unavailable_region_count"));
    }
  }

  private static RegionMetadata region(String regionId, byte[] startKey,
      byte[] endKey, String leaderId) {
    return new RegionMetadata(regionId, new KeyRange(startKey, endKey), 1,
        new VirtualNodeMetadata("vn-" + regionId, 1, leaderId,
            Arrays.asList(
                new VirtualNodeReplica("node-a", ReplicaRole.DATA_VOTER),
                new VirtualNodeReplica("node-b", ReplicaRole.DATA_VOTER),
                new VirtualNodeReplica("witness-a",
                    ReplicaRole.WITNESS_VOTER)),
            0, 0, 0));
  }
}
