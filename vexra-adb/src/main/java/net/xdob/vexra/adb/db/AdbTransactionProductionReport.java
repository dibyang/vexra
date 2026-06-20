package net.xdob.vexra.adb.db;

/**
 * ADB GA-04 事务最小生产化结构化验收报告。
 *
 * <p>该报告不直接执行事务，而是固定 release profile 或 CI 需要提交的事务证据字段：
 * 单 region 事务必须可用，跨 region 事务必须默认拒绝，冲突、lock resolve、safe point
 * 和 GC 保护必须都有可审计结果。后续真实执行器可以把 JUnit、长稳或多进程演练结果映射到
 * 这个对象，再交给 {@link AdbTransactionProductionGate} 判定。</p>
 */
public final class AdbTransactionProductionReport {
  private final String scenarioName;
  private final boolean singleRegionCommitPassed;
  private final boolean crossRegionRejected;
  private final boolean conflictSqlStateStable;
  private final boolean lockResolveRollbackPassed;
  private final boolean lockResolveRollForwardPassed;
  private final boolean lockResolveIdempotent;
  private final boolean activeTransactionSafePointProtected;
  private final boolean backupSafePointProtected;
  private final boolean gcKeepsLatestCommittedVersion;

  /**
   * 创建事务生产化验收报告。
   *
   * @param scenarioName 场景名称或执行批次名称
   * @param singleRegionCommitPassed 单 region 提交路径是否通过
   * @param crossRegionRejected 跨 region 事务是否默认拒绝
   * @param conflictSqlStateStable 冲突是否映射到稳定 SQLState / errorCode
   * @param lockResolveRollbackPassed 过期未提交 primary 是否可 rollback
   * @param lockResolveRollForwardPassed primary 已提交时 secondary 是否可前滚
   * @param lockResolveIdempotent 重复 resolve 是否幂等
   * @param activeTransactionSafePointProtected 活跃事务是否阻塞 safe point 推进
   * @param backupSafePointProtected 备份保护点是否阻塞 safe point 推进
   * @param gcKeepsLatestCommittedVersion GC 是否保留每个 logical key 最新提交版本
   */
  public AdbTransactionProductionReport(String scenarioName,
      boolean singleRegionCommitPassed, boolean crossRegionRejected,
      boolean conflictSqlStateStable, boolean lockResolveRollbackPassed,
      boolean lockResolveRollForwardPassed, boolean lockResolveIdempotent,
      boolean activeTransactionSafePointProtected,
      boolean backupSafePointProtected,
      boolean gcKeepsLatestCommittedVersion) {
    this.scenarioName = normalize(scenarioName, "scenarioName");
    this.singleRegionCommitPassed = singleRegionCommitPassed;
    this.crossRegionRejected = crossRegionRejected;
    this.conflictSqlStateStable = conflictSqlStateStable;
    this.lockResolveRollbackPassed = lockResolveRollbackPassed;
    this.lockResolveRollForwardPassed = lockResolveRollForwardPassed;
    this.lockResolveIdempotent = lockResolveIdempotent;
    this.activeTransactionSafePointProtected =
        activeTransactionSafePointProtected;
    this.backupSafePointProtected = backupSafePointProtected;
    this.gcKeepsLatestCommittedVersion = gcKeepsLatestCommittedVersion;
  }

  public String getScenarioName() {
    return scenarioName;
  }

  public boolean isSingleRegionCommitPassed() {
    return singleRegionCommitPassed;
  }

  public boolean isCrossRegionRejected() {
    return crossRegionRejected;
  }

  public boolean isConflictSqlStateStable() {
    return conflictSqlStateStable;
  }

  public boolean isLockResolveRollbackPassed() {
    return lockResolveRollbackPassed;
  }

  public boolean isLockResolveRollForwardPassed() {
    return lockResolveRollForwardPassed;
  }

  public boolean isLockResolveIdempotent() {
    return lockResolveIdempotent;
  }

  public boolean isActiveTransactionSafePointProtected() {
    return activeTransactionSafePointProtected;
  }

  public boolean isBackupSafePointProtected() {
    return backupSafePointProtected;
  }

  public boolean isGcKeepsLatestCommittedVersion() {
    return gcKeepsLatestCommittedVersion;
  }

  private static String normalize(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return value.trim();
  }
}
