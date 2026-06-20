package net.xdob.vexra.adb.db;

/**
 * ADB 进程级恢复演练场景。
 *
 * <p>这些场景对应 GA-02 和 GA-07 要求的最小 kill/restart 覆盖面。真实执行可以来自
 * 多进程 JUnit、发行包脚本或 CI release profile，但提交到 release gate 的证据必须使用
 * 相同枚举，避免遗漏某一类故障。</p>
 */
public enum AdbRecoveryDrillScenario {
  /** kill 当前 leader 后由剩余 data + witness 维持多数派并恢复读写。 */
  KILL_LEADER,

  /** kill follower 后验证 leader 仍可写，follower 重启后能追平。 */
  KILL_FOLLOWER,

  /** kill witness 后验证 data 节点行为符合多数派和写入限制预期。 */
  KILL_WITNESS,

  /** 全集群停止并重启后验证数据、路由和 Raft 状态恢复。 */
  FULL_CLUSTER_RESTART
}
