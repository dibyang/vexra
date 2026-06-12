package net.xdob.vexra.adb.db;

/**
 * ADB region 事务提交阶段。
 *
 * <p>该枚举用于 region Raft/RPC commit client 的传输边界，和
 * {@link AdbRegionCommitClient} 的 prewrite、commit、rollback 三个方法一一对应。</p>
 */
public enum AdbRegionCommitPhase {
  /** 写入 primary/secondary lock 或等价预写状态。 */
  PREWRITE,

  /** 提交已预写的 region participant。 */
  COMMIT,

  /** 回滚已预写或待清理的 region participant。 */
  ROLLBACK
}
