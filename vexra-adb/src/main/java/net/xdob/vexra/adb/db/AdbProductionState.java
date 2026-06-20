package net.xdob.vexra.adb.db;

/**
 * ADB 生产 guard 的校验状态。
 */
public enum AdbProductionState {
  /** 尚未完成配置校验。 */
  UNVERIFIED,

  /** 单机模式已就绪。 */
  SINGLE_READY,

  /** 生产 MVP 集群模式已就绪。 */
  CLUSTER_READY,

  /** 降级只读，当前最小 guard 只建模状态，不主动放行写入。 */
  DEGRADED_READONLY,

  /** 配置或拓扑非法。 */
  REJECTED
}
