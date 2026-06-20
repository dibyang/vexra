package net.xdob.vexra.adb.db;

/**
 * ADB commit 崩溃注入点。
 *
 * <p>这些注入点对应 GA-02 数据安全闭环要求的 commit 关键阶段。每个阶段都定义恢复后
 * 应看到的 durable marker 终态；`BEFORE_PREWRITE` 代表尚未产生 durable intent，因此
 * 期望没有 marker。</p>
 */
public enum AdbCommitCrashInjectionPoint {
  /** prewrite 前崩溃，事务尚未产生 durable intent。 */
  BEFORE_PREWRITE(null),

  /** prewrite 后、raft commit 前崩溃，恢复后必须回滚。 */
  AFTER_PREWRITE_BEFORE_RAFT(AdbDurableCommitState.ROLLED_BACK),

  /** raft commit 后、store commit 前崩溃，恢复后必须前滚到本地提交。 */
  AFTER_RAFT_BEFORE_STORE(AdbDurableCommitState.STORE_COMMITTED),

  /** store commit 后、reply 前崩溃，客户端重试必须返回已提交。 */
  AFTER_STORE_BEFORE_REPLY(AdbDurableCommitState.REPLIED);

  private final AdbDurableCommitState expectedFinalState;

  AdbCommitCrashInjectionPoint(
      AdbDurableCommitState expectedFinalState) {
    this.expectedFinalState = expectedFinalState;
  }

  /**
   * 返回该注入点恢复后的期望 marker 终态。
   *
   * @return 期望状态；prewrite 前崩溃没有 marker，返回 null
   */
  public AdbDurableCommitState getExpectedFinalState() {
    return expectedFinalState;
  }
}
