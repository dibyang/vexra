package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.IndexBuildState;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.adb.ldb.LdbStore;
import net.xdob.vexra.cluster.ddl.DdlJob;
import net.xdob.vexra.cluster.ddl.DdlJobState;
import net.xdob.vexra.cluster.ddl.SchemaVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADB Online DDL runtime controller 测试。
 *
 * <p>测试覆盖 ADB-Runtime-10 的最小运行时闭环：ADD_INDEX schema version 推进、
 * BUILDING/READY 状态切换、backfill 断点恢复和失败状态迁移。</p>
 */
class AdbOnlineDdlRuntimeControllerTest {
  @TempDir
  File tempDir;

  /**
   * 验证 ADD_INDEX job 可以从 RUNNING/BACKFILLING 推进到 PUBLIC。
   */
  @Test
  void shouldPublishAddIndexAndMarkIndexReady() throws Exception {
    TabId tabId = TabId.of(1, 0L);
    try (LdbStore store = new LdbStore(new File(tempDir, "ddl-store")
        .getAbsolutePath())) {
      TxnManager txnManager = new TxnManager(store);
      AdbOnlineDdlRuntimeController controller =
          new AdbOnlineDdlRuntimeController(txnManager);

      DdlJob running = controller.startAddIndex("job-1", tabId, 7,
          new SchemaVersion(1));
      DdlJob backfilling = controller.beginBackfill(running);
      DdlJob progressed = controller.advanceBackfill(backfilling,
          key("row-10"), 10);
      DdlJob published = controller.publishAddIndex(progressed, tabId, 7);

      assertEquals(DdlJobState.RUNNING, running.getState());
      assertEquals(2, running.getSchemaVersion().getVersion());
      assertEquals(DdlJobState.BACKFILLING, backfilling.getState());
      assertArrayEquals(key("row-10"),
          progressed.getBackfillProgress().getLastCompletedKey());
      assertEquals(10, progressed.getBackfillProgress().getCompletedRows());
      assertEquals(DdlJobState.PUBLIC, published.getState());
      assertEquals(3, published.getSchemaVersion().getVersion());
      assertEquals(IndexBuildState.READY,
          txnManager.getIndexBuildState(tabId, 7));
    }
  }

  /**
   * 验证 controller 重建后可基于已有 job 继续推进 backfill 断点。
   */
  @Test
  void shouldResumeBackfillFromExistingProgress() throws Exception {
    TabId tabId = TabId.of(2, 0L);
    try (LdbStore store = new LdbStore(new File(tempDir, "resume-store")
        .getAbsolutePath())) {
      TxnManager txnManager = new TxnManager(store);
      AdbOnlineDdlRuntimeController controller =
          new AdbOnlineDdlRuntimeController(txnManager);
      DdlJob backfilling = controller.beginBackfill(
          controller.startAddIndex("job-2", tabId, 3,
              new SchemaVersion(5)));
      DdlJob checkpoint = controller.advanceBackfill(backfilling,
          key("row-20"), 20);

      AdbOnlineDdlRuntimeController rebuilt =
          new AdbOnlineDdlRuntimeController(txnManager);
      DdlJob resumed = rebuilt.advanceBackfill(checkpoint, key("row-30"), 30);

      assertEquals(30, resumed.getBackfillProgress().getCompletedRows());
      assertArrayEquals(key("row-30"),
          resumed.getBackfillProgress().getLastCompletedKey());
      assertEquals(IndexBuildState.BUILDING,
          txnManager.getIndexBuildState(tabId, 3));
    }
  }

  /**
   * 验证 backfill 进度不能倒退，避免恢复后覆盖较新的断点。
   */
  @Test
  void shouldRejectBackfillProgressRegression() throws Exception {
    TabId tabId = TabId.of(3, 0L);
    try (LdbStore store = new LdbStore(new File(tempDir, "regress-store")
        .getAbsolutePath())) {
      AdbOnlineDdlRuntimeController controller =
          new AdbOnlineDdlRuntimeController(new TxnManager(store));
      DdlJob progressed = controller.advanceBackfill(
          controller.beginBackfill(controller.startAddIndex("job-3", tabId, 1,
              new SchemaVersion(1))), key("row-10"), 10);

      assertThrows(IllegalArgumentException.class,
          () -> controller.advanceBackfill(progressed, key("row-09"), 9));
    }
  }

  /**
   * 验证运行中的 ADD_INDEX job 可以进入 FAILED。
   */
  @Test
  void shouldFailRunningAddIndexJob() throws Exception {
    TabId tabId = TabId.of(4, 0L);
    try (LdbStore store = new LdbStore(new File(tempDir, "fail-store")
        .getAbsolutePath())) {
      AdbOnlineDdlRuntimeController controller =
          new AdbOnlineDdlRuntimeController(new TxnManager(store));
      DdlJob running = controller.startAddIndex("job-4", tabId, 1,
          new SchemaVersion(1));

      DdlJob failed = controller.fail(running);

      assertEquals(DdlJobState.FAILED, failed.getState());
    }
  }

  private static byte[] key(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
