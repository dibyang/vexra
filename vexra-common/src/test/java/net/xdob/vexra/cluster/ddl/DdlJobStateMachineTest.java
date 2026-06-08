package net.xdob.vexra.cluster.ddl;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Online DDL job 状态机回归测试。
 *
 * <p>测试覆盖 ADB-Cluster-06 的最小公共模型：schema version 推进、add index
 * backfill 断点、非法状态跳转拒绝和 rollback 路径。</p>
 */
class DdlJobStateMachineTest {
  private final DdlJobStateMachine stateMachine = new DdlJobStateMachine();

  /**
   * 验证 DDL 正常从 pending 推进到 public，并在关键 schema 变更点递增版本。
   */
  @Test
  void shouldAdvanceDdlJobToPublic() {
    DdlJob job = DdlJob.pending("job-1", "ADD_INDEX",
        new SchemaVersion(1));

    DdlJob running = stateMachine.transition(job, DdlJobState.RUNNING);
    DdlJob backfilling = stateMachine.transition(running,
        DdlJobState.BACKFILLING);
    DdlJob published = stateMachine.transition(backfilling,
        DdlJobState.PUBLIC);

    assertEquals(DdlJobState.PUBLIC, published.getState());
    assertEquals(3, published.getSchemaVersion().getVersion());
  }

  /**
   * 验证回填进度可以保存断点。
   */
  @Test
  void shouldKeepBackfillProgress() {
    DdlJob job = DdlJob.pending("job-1", "ADD_INDEX",
        new SchemaVersion(1)).withBackfillProgress(
        new IndexBackfillProgress(bytes("k100"), 100));

    assertEquals(100, job.getBackfillProgress().getCompletedRows());
    assertArrayEquals(bytes("k100"),
        job.getBackfillProgress().getLastCompletedKey());
  }

  /**
   * 验证非法状态跳转会被拒绝。
   */
  @Test
  void shouldRejectIllegalDdlTransition() {
    DdlJob job = DdlJob.pending("job-1", "ADD_INDEX",
        new SchemaVersion(1));

    assertThrows(IllegalStateException.class,
        () -> stateMachine.transition(job, DdlJobState.PUBLIC));
  }

  /**
   * 验证运行中 DDL 可以进入 rollback，再进入 failed。
   */
  @Test
  void shouldRollbackAndFailDdlJob() {
    DdlJob running = stateMachine.transition(
        DdlJob.pending("job-1", "ADD_INDEX", new SchemaVersion(1)),
        DdlJobState.RUNNING);

    DdlJob rollback = stateMachine.transition(running, DdlJobState.ROLLBACK);
    DdlJob failed = stateMachine.transition(rollback, DdlJobState.FAILED);

    assertEquals(DdlJobState.ROLLBACK, rollback.getState());
    assertEquals(DdlJobState.FAILED, failed.getState());
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
