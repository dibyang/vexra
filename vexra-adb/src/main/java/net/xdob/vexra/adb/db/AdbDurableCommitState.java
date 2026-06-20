package net.xdob.vexra.adb.db;

/**
 * ADB durable commit marker 状态。
 *
 * <p>该状态机用于 ADB-GA-02 的数据安全闭环，描述 SQL commit 在复制、落盘、
 * 回复客户端之间的恢复边界。状态一旦越过 RAFT_COMMITTED，就不能再回滚业务数据。</p>
 */
public enum AdbDurableCommitState {
  /** 已写入 durable intent / prewrite 信息。 */
  PREWRITTEN,

  /** 已获得 region/Raft 多数派提交。 */
  RAFT_COMMITTED,

  /** 已把 committed version 持久化到 store。 */
  STORE_COMMITTED,

  /** 已向客户端返回成功。 */
  REPLIED,

  /** prewrite 后、提交前已经回滚。 */
  ROLLED_BACK;

  /**
   * 判断状态是否为终态。
   *
   * @return 终态返回 true
   */
  public boolean isTerminal() {
    return this == REPLIED || this == ROLLED_BACK;
  }
}
