package net.xdob.vexra.adb.db;

/**
 * ADB commit marker 恢复动作。
 */
public enum AdbCommitRecoveryAction {
  /** 无需处理或可以丢弃。 */
  DISCARD,

  /** prewrite 后尚未提交，需要回滚。 */
  ROLLBACK,

  /** 已经复制提交，需要前滚到 store committed。 */
  ROLL_FORWARD,

  /** 已经持久化提交，客户端重试应返回已提交结果。 */
  RETURN_COMMITTED
}
