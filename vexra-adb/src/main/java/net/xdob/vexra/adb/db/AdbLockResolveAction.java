package net.xdob.vexra.adb.db;

/**
 * ADB lock resolve 动作结果。
 *
 * <p>resolver 通过该枚举向调用方说明当前 lock 是继续等待，还是已经通过
 * rollback 清理。后续接入 primary/secondary resolve 时可以在该边界扩展
 * committed/forward 等结果。</p>
 */
public enum AdbLockResolveAction {
  /**
   * lock 尚未过期或无需处理，调用方应继续等待或稍后重试。
   */
  WAIT,

  /**
   * lock 已过期，resolver 已通过 rollback 清理该事务的 durable intent。
   */
  ROLLED_BACK,

  /**
   * primary 已提交，resolver 已按 primary commitTs 前滚当前 store 中的 remaining intent。
   */
  ROLLED_FORWARD
}
