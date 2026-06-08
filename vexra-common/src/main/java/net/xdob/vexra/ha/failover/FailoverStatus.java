package net.xdob.vexra.ha.failover;

/**
 * 故障切换规划结果状态。
 */
public enum FailoverStatus {
  /** 当前 leader 可达且满足多数派，可以继续写入。 */
  KEEP_LEADER,

  /** 当前 leader 不可用，但可达 data voter 能在多数派下接管。 */
  PROMOTE_DATA_LEADER,

  /** 无法形成多数派，只能只读或不可用。 */
  DEGRADED_READONLY,

  /** 拓扑中没有可接管的 data voter。 */
  UNAVAILABLE
}
