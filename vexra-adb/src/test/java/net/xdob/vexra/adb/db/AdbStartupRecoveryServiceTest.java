package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.adb.ldb.LdbStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ADB 启动恢复服务测试。
 *
 * <p>测试覆盖 `DbStoreEngine` 首次打开 store 时自动执行本地 durable commit marker
 * 恢复，避免进程重启后仅能手动调用恢复执行器。</p>
 */
class AdbStartupRecoveryServiceTest {
  @TempDir
  File tempDir;

  /**
   * 验证 DbStoreEngine 首次打开 LDB store 后会自动前滚 RAFT_COMMITTED marker。
   */
  @Test
  void shouldRunStartupRecoveryWhenDbStoreEngineOpensStore()
      throws Exception {
    File dbDir = new File(tempDir, "engine-startup-recovery");
    try (LdbStore store = new LdbStore(dbDir.getAbsolutePath())) {
      AdbPersistentDurableCommitRecorder recorder =
          new AdbPersistentDurableCommitRecorder(store);
      AdbDurableCommitMarker marker = recorder.prewritten(request("r1"));
      recorder.raftCommitted(marker);
    }

    DbStore store = DbStoreEngine.getOrCreate(DbStoreType.LDB,
        dbDir.getAbsolutePath(), new Properties());
    try {
      AdbPersistentDurableCommitRecorder recorder =
          new AdbPersistentDurableCommitRecorder(store);
      Collection<AdbDurableCommitMarker> markers = recorder.snapshot();

      assertEquals(AdbDurableCommitState.REPLIED,
          marker(markers, "r1", 100).getState());
    } finally {
      DbStoreEngine.close(dbDir.getAbsolutePath());
    }
  }

  private static AdbRegionCommitRequest request(String regionId) {
    RowKey key = RowKey.of(TabId.of(1, 0L), 1);
    return new AdbRegionCommitRequest(regionId, 1, "node-a", 100,
        10, 20, regionId, key, 3000, true,
        Collections.singletonList(key), Collections.emptyList());
  }

  private static AdbDurableCommitMarker marker(
      Collection<AdbDurableCommitMarker> markers, String regionId,
      long txnId) {
    for (AdbDurableCommitMarker marker : markers) {
      if (marker.getTxnId() == txnId
          && marker.getRegionId().equals(regionId)) {
        return marker;
      }
    }
    throw new AssertionError("missing marker, txnId=" + txnId
        + ", regionId=" + regionId);
  }
}
