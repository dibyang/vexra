package net.xdob.vexra.cluster.ops;

/**
 * 集群健康状态。
 */
public enum ClusterHealthStatus {
  /** 所有关键 region 可写且无阻塞任务。 */
  HEALTHY,

  /** 部分 region 或任务异常，但系统仍可降级服务。 */
  DEGRADED,

  /** 无法满足一致性或关键服务不可用。 */
  UNAVAILABLE
}
