package net.xdob.vexra.adb.db;

/**
 * ADB durable commit 恢复执行结果。
 *
 * <p>该对象用于启动恢复、测试和后续 system table/doctor 暴露恢复证据。它只记录本轮
 * 扫描决策被如何执行，不持有底层资源，线程安全由调用方通过不可变发布保证。</p>
 */
public final class AdbCommitRecoveryResult {
  private final int scanned;
  private final int rolledBack;
  private final int rolledForward;
  private final int returnedCommitted;
  private final int discarded;

  /**
   * 创建恢复执行结果。
   *
   * @param scanned 扫描到的决策数量
   * @param rolledBack 已回滚数量
   * @param rolledForward 已前滚数量
   * @param returnedCommitted 已确认为提交成功数量
   * @param discarded 已丢弃数量
   */
  public AdbCommitRecoveryResult(int scanned, int rolledBack,
      int rolledForward, int returnedCommitted, int discarded) {
    this.scanned = scanned;
    this.rolledBack = rolledBack;
    this.rolledForward = rolledForward;
    this.returnedCommitted = returnedCommitted;
    this.discarded = discarded;
  }

  public int getScanned() {
    return scanned;
  }

  public int getRolledBack() {
    return rolledBack;
  }

  public int getRolledForward() {
    return rolledForward;
  }

  public int getReturnedCommitted() {
    return returnedCommitted;
  }

  public int getDiscarded() {
    return discarded;
  }
}
