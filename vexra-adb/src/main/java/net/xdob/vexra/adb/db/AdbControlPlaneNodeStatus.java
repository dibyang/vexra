package net.xdob.vexra.adb.db;

/**
 * ADB 控制面中的节点生命周期状态。
 *
 * <p>该状态由轻量控制面根据注册、心跳、故障检测和运维动作推进。当前第一批
 * GA-03 实现只在健康心跳写入时落为 {@link #UP}，后续 heartbeat service
 * 会补齐超时检测和显式下线状态流转。</p>
 */
public enum AdbControlPlaneNodeStatus {
  /** 节点刚注册，尚未参与写入和 leader 分配。 */
  JOINING,

  /** 节点心跳健康，可以参与路由和 quorum。 */
  UP,

  /** 节点出现短暂心跳缺失，暂停新的 leader 分配。 */
  SUSPECT,

  /** 节点超过故障阈值，被控制面视为不可用。 */
  DOWN,

  /** 节点重启后正在追赶日志，只允许 catch-up。 */
  RECOVERING,

  /** 节点被显式移除，不允许自动重新加入。 */
  DECOMMISSIONED
}
