package net.xdob.vexra.cluster.txn;

/**
 * 两阶段提交状态。
 */
public enum TwoPhaseCommitState {
  /** 事务已创建，尚未 prewrite。 */
  CREATED,

  /** 所有参与 region 已完成 prewrite。 */
  PREWRITTEN,

  /** 事务已提交。 */
  COMMITTED,

  /** 事务已回滚。 */
  ROLLED_BACK
}
