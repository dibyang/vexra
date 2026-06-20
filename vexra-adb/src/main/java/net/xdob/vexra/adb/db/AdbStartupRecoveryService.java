package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.DbStore;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * ADB 启动恢复服务。
 *
 * <p>该服务位于本地 {@link DbStore} 首次打开之后、对外承载 SQL 写入之前。它扫描
 * durable commit marker，生成恢复决策，并调用 {@link AdbCommitRecoveryExecutor}
 * 执行本地 rollback / roll-forward / return-committed。该类不持有后台线程，调用方可以
 * 在启动流程中同步执行，确保不确定事务不会被静默遗留。</p>
 */
public final class AdbStartupRecoveryService {
  private final AdbPersistentDurableCommitRecorder recorder;
  private final AdbCommitRecoveryScanner scanner;
  private final AdbCommitRecoveryExecutor executor;

  /**
   * 基于本地 store 创建启动恢复服务。
   *
   * @param store ADB 底层 store
   */
  public AdbStartupRecoveryService(DbStore store) {
    Objects.requireNonNull(store, "store == null");
    this.recorder = new AdbPersistentDurableCommitRecorder(store);
    this.scanner = new AdbCommitRecoveryScanner();
    this.executor = new AdbCommitRecoveryExecutor(store, recorder);
  }

  /**
   * 执行一次同步启动恢复。
   *
   * @return 本轮恢复执行结果
   * @throws SQLException marker 扫描、store 恢复或 marker 状态更新失败时抛出
   */
  public AdbCommitRecoveryResult recoverOnce() throws SQLException {
    List<AdbCommitRecoveryDecision> decisions =
        scanner.scan(recorder.snapshot());
    return executor.recover(decisions);
  }
}
