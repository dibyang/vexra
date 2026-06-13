package net.xdob.vexra.adb.db;

/**
 * ADB 故障注入类型。
 *
 * <p>这些类型对应 ADB-Prod-06 发布前必须覆盖的核心故障域。</p>
 */
public enum AdbFaultInjectionType {
  /** 网络分区或 region leader 不可达。 */
  NETWORK_PARTITION,

  /** leader 主动切换或任期推进。 */
  LEADER_TRANSFER,

  /** 磁盘读写错误、checkpoint/restore 失败或存储目录不可用。 */
  DISK_FAULT,

  /** 数据节点进程重启。 */
  NODE_RESTART,

  /** witness 节点丢失或不可达。 */
  WITNESS_LOSS
}
