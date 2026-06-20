package net.xdob.vexra.adb.db;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * ADB 控制面节点心跳状态机服务。
 *
 * <p>该服务是 GA-03 的轻量 heartbeat service 雏形：它读取持久化节点记录，
 * 根据最近心跳时间把健康节点保守推进到 `SUSPECT` 或 `DOWN`。显式运维状态
 * `RECOVERING` 和 `DECOMMISSIONED` 不由该后台评估自动改写，避免后台检测覆盖
 * 运维动作。</p>
 */
public final class AdbNodeHeartbeatService {
  private final AdbPersistentControlPlaneStore store;
  private final long suspectAfterMillis;
  private final long downAfterMillis;

  /**
   * 创建节点心跳状态机服务。
   *
   * @param store 持久化控制面 store
   * @param suspectAfterMillis 超过该时长未心跳则进入 SUSPECT
   * @param downAfterMillis 超过该时长未心跳则进入 DOWN
   */
  public AdbNodeHeartbeatService(AdbPersistentControlPlaneStore store,
      long suspectAfterMillis, long downAfterMillis) {
    this.store = Objects.requireNonNull(store, "store == null");
    if (suspectAfterMillis < 0) {
      throw new IllegalArgumentException("suspectAfterMillis is negative: "
          + suspectAfterMillis);
    }
    if (downAfterMillis <= suspectAfterMillis) {
      throw new IllegalArgumentException("downAfterMillis must be greater "
          + "than suspectAfterMillis");
    }
    this.suspectAfterMillis = suspectAfterMillis;
    this.downAfterMillis = downAfterMillis;
  }

  /**
   * 持久化健康心跳。
   *
   * @param heartbeat 节点心跳
   * @throws SQLException 底层持久化失败时抛出
   */
  public void heartbeat(AdbNodeHeartbeat heartbeat) throws SQLException {
    store.heartbeat(heartbeat);
  }

  /**
   * 根据当前时间评估所有节点的心跳超时状态。
   *
   * @param nowMillis 当前时间戳
   * @return 本次评估更新的节点数量
   * @throws SQLException 底层读取或写入失败时抛出
   */
  public int evaluateTimeouts(long nowMillis) throws SQLException {
    if (nowMillis < 0) {
      throw new IllegalArgumentException("nowMillis is negative: "
          + nowMillis);
    }
    int updated = 0;
    List<AdbControlPlaneNodeRecord> nodes = store.listNodes();
    for (AdbControlPlaneNodeRecord node : nodes) {
      AdbControlPlaneNodeStatus next = nextStatus(node, nowMillis);
      if (next != node.getStatus()) {
        store.persistNodeRecord(node.withStatus(next));
        updated++;
      }
    }
    return updated;
  }

  private AdbControlPlaneNodeStatus nextStatus(
      AdbControlPlaneNodeRecord node, long nowMillis) {
    AdbControlPlaneNodeStatus current = node.getStatus();
    if (current == AdbControlPlaneNodeStatus.RECOVERING
        || current == AdbControlPlaneNodeStatus.DECOMMISSIONED
        || current == AdbControlPlaneNodeStatus.JOINING) {
      return current;
    }
    long lag = nowMillis - node.getLastHeartbeatMillis();
    if (lag < 0) {
      return current;
    }
    if (lag >= downAfterMillis) {
      return AdbControlPlaneNodeStatus.DOWN;
    }
    if (lag >= suspectAfterMillis) {
      return AdbControlPlaneNodeStatus.SUSPECT;
    }
    return current;
  }
}
