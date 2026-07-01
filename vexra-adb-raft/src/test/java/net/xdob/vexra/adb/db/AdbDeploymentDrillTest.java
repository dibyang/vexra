package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.ha2.AdbRClientFactory;
import net.xdob.vexra.adb.ha2.AdbRClientRegistry;
import net.xdob.vexra.adb.ha2.AdbRClientRegistryRefresher;
import net.xdob.vexra.adb.ha2.RClient;
import net.xdob.vexra.adb.ldb.LdbStore;
import net.xdob.vexra.cluster.ops.BackupRestoreMode;
import net.xdob.vexra.cluster.ops.BackupRestorePlan;
import net.xdob.vexra.cluster.ops.RollingUpgradePlan;
import net.xdob.vexra.cluster.region.KeyRange;
import net.xdob.vexra.cluster.region.RegionMetadata;
import net.xdob.vexra.ha.ReplicaRole;
import net.xdob.vexra.ha.VirtualNodeMetadata;
import net.xdob.vexra.ha.VirtualNodeReplica;
import net.xdob.vexra.proto.adb.ReadRequest;
import net.xdob.vexra.proto.adb.ReadResponse;
import net.xdob.vexra.proto.adb.WriteRequest;
import net.xdob.vexra.proto.adb.WriteResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADB 部署演练测试。
 *
 * <p>测试覆盖 ADB-Prod-05 的 registry 预检、system row/metrics、备份恢复演练
 * 和滚动升级演练。</p>
 */
class AdbDeploymentDrillTest {
  @TempDir
  File tempDir;

  /**
   * 验证部署预检会刷新 leader client registry 并输出运维证据。
   */
  @Test
  void shouldRunDeploymentPreflightAndExposeEvidence() throws Exception {
    try (LdbStore store = new LdbStore(new File(tempDir, "preflight-store")
        .getAbsolutePath())) {
      InMemoryAdbControlPlaneClient controlPlane =
          new InMemoryAdbControlPlaneClient(Arrays.asList(
              region("r1", range(0, 1), "node-a", 1, 1),
              region("r2", range(1, 2), "node-b", 1, 1)), 100);
      AdbRClientRegistry registry = new AdbRClientRegistry();
      AdbDeploymentDrill drill = drill(store, controlPlane, registry);

      AdbDeploymentPreflightResult result = drill.preflight(false);

      assertTrue(result.isReadyForTraffic());
      assertEquals(2,
          result.getRegistryRefreshResult().getRegisteredClients());
      assertTrue(registry.get("node-a").isPresent());
      assertTrue(registry.get("node-b").isPresent());
      assertEquals("HEALTHY", result.getSystemTableRow().get("health_status"));
      assertEquals(0,
          result.getMetrics().get("vexra_cluster_unavailable_region_count"));
      assertEquals(3, result.getStartupCommands().size());
    }
  }

  /**
   * 验证部署演练入口可以执行 FULL backup/restore drill。
   */
  @Test
  void shouldRunBackupRestoreDrillFromDeploymentFacade() throws Exception {
    File backupDir = new File(tempDir, "backup-dir");
    try (LdbStore store = new LdbStore(new File(tempDir, "backup-store")
        .getAbsolutePath())) {
      InMemoryAdbControlPlaneClient controlPlane =
          new InMemoryAdbControlPlaneClient(Collections.singletonList(
              region("r1", range(0, 1), "node-a", 1, 1)), 100);
      AdbDeploymentDrill drill = drill(store, controlPlane,
          new AdbRClientRegistry());
      BackupRestorePlan plan = new BackupRestorePlan("deploy-backup",
          BackupRestoreMode.FULL, Collections.singletonList("r1"),
          backupDir.getAbsolutePath(), 1);

      drill.runBackupRestoreDrill(plan);

      assertTrue(backupDir.exists());
    }
  }

  /**
   * 验证滚动升级演练会按节点顺序完成。
   */
  @Test
  void shouldRunRollingUpgradeDrillWhenClusterWritable() throws Exception {
    try (LdbStore store = new LdbStore(new File(tempDir, "upgrade-store")
        .getAbsolutePath())) {
      InMemoryAdbControlPlaneClient controlPlane =
          new InMemoryAdbControlPlaneClient(Collections.singletonList(
              region("r1", range(0, 1), "node-a", 1, 1)), 100);
      AdbDeploymentDrill drill = drill(store, controlPlane,
          new AdbRClientRegistry());
      RollingUpgradePlan plan = new RollingUpgradePlan("0.5.0",
          Arrays.asList("node-a", "node-b", "witness-a"), null);

      RollingUpgradePlan completed = drill.runRollingUpgradeDrill(plan);

      assertTrue(completed.isComplete());
      assertEquals("", completed.nextNode());
    }
  }

  /**
   * 验证无可写 region 时滚动升级演练会失败。
   */
  @Test
  void shouldRejectRollingUpgradeWhenClusterUnavailable() throws Exception {
    try (LdbStore store = new LdbStore(new File(tempDir, "bad-upgrade-store")
        .getAbsolutePath())) {
      InMemoryAdbControlPlaneClient controlPlane =
          new InMemoryAdbControlPlaneClient(Collections.singletonList(
              region("r1", range(0, 1), "", 1, 1)), 100);
      AdbDeploymentDrill drill = drill(store, controlPlane,
          new AdbRClientRegistry());
      RollingUpgradePlan plan = new RollingUpgradePlan("0.5.0",
          Collections.singletonList("node-a"), null);

      assertThrows(SQLException.class,
          () -> drill.runRollingUpgradeDrill(plan));
    }
  }

  private static AdbDeploymentDrill drill(LdbStore store,
      InMemoryAdbControlPlaneClient controlPlane, AdbRClientRegistry registry) {
    AdbRuntimeOperationsBridge bridge =
        new AdbRuntimeOperationsBridge(store, controlPlane, "0.5.0-test");
    AdbRClientFactory factory = replicaId -> new FakeRClient();
    return new AdbDeploymentDrill(deploymentPlan(), controlPlane, bridge,
        new AdbRClientRegistryRefresher(registry, factory));
  }

  private static AdbDeploymentPlan deploymentPlan() {
    return new AdbDeploymentPlan(
        new AdbDistributedRuntimeOptions(true, true, true),
        "java", "vexra-adb-node.jar", Arrays.asList(
        node("node-a", 17701, "/data/a", AdbDeploymentNodeRole.DATA_NODE),
        node("node-b", 17702, "/data/b", AdbDeploymentNodeRole.DATA_NODE),
        node("witness-a", 17703, "/data/w",
            AdbDeploymentNodeRole.WITNESS_NODE)));
  }

  private static AdbDeploymentNodeSpec node(String nodeId, int port,
      String dataDir, AdbDeploymentNodeRole role) {
    return new AdbDeploymentNodeSpec(nodeId, "127.0.0.1", port, dataDir,
        role, "/tls/" + nodeId + ".pem", "/priv/" + nodeId + ".json");
  }

  private static RegionMetadata region(String regionId, KeyRange range,
      String leaderId, long regionEpoch, long replicaEpoch) {
    return new RegionMetadata(regionId, range,
        regionEpoch, new VirtualNodeMetadata("vn-" + regionId, replicaEpoch,
        leaderId, Arrays.asList(
        new VirtualNodeReplica("node-a", ReplicaRole.DATA_VOTER),
        new VirtualNodeReplica("node-b", ReplicaRole.DATA_VOTER),
        new VirtualNodeReplica("witness-a", ReplicaRole.WITNESS_VOTER)),
        0, 0, 0));
  }

  private static KeyRange range(int start, int end) {
    return new KeyRange(new byte[]{(byte) start}, new byte[]{(byte) end});
  }

  private static final class FakeRClient implements RClient {
    @Override
    public ReadResponse sendReadRequest(ReadRequest request) {
      return ReadResponse.getDefaultInstance();
    }

    @Override
    public WriteResponse sendWriteRequest(WriteRequest request) {
      return WriteResponse.getDefaultInstance();
    }

    @Override
    public CompletableFuture<ReadResponse> sendReadRequestAsync(
        ReadRequest request) {
      return CompletableFuture.completedFuture(ReadResponse.getDefaultInstance());
    }

    @Override
    public CompletableFuture<WriteResponse> sendWriteRequestAsync(
        WriteRequest request) {
      return CompletableFuture.completedFuture(
          WriteResponse.getDefaultInstance());
    }

    @Override
    public void close() throws IOException {
      // fake client does not own resources
    }
  }
}
