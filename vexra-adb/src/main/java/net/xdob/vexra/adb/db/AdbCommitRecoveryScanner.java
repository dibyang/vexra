package net.xdob.vexra.adb.db;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * ADB commit marker 恢复扫描器。
 *
 * <p>扫描器只负责把 marker 状态映射为恢复动作，不直接修改 store。真实恢复 worker
 * 可以基于该决策调用 rollback、roll-forward 或幂等返回逻辑。</p>
 */
public final class AdbCommitRecoveryScanner {

  /**
   * 扫描一批 durable commit marker。
   *
   * @param markers marker 集合
   * @return 恢复决策列表
   */
  public List<AdbCommitRecoveryDecision> scan(
      Collection<AdbDurableCommitMarker> markers) {
    Objects.requireNonNull(markers, "markers == null");
    List<AdbCommitRecoveryDecision> decisions = new ArrayList<>();
    for (AdbDurableCommitMarker marker : markers) {
      decisions.add(decide(marker));
    }
    return Collections.unmodifiableList(decisions);
  }

  /**
   * 为单个 marker 生成恢复决策。
   *
   * @param marker durable commit marker
   * @return 恢复决策
   */
  public AdbCommitRecoveryDecision decide(AdbDurableCommitMarker marker) {
    Objects.requireNonNull(marker, "marker == null");
    switch (marker.getState()) {
      case PREWRITTEN:
        return decision(marker, AdbCommitRecoveryAction.ROLLBACK,
            "prewrite finished but raft commit is not durable");
      case RAFT_COMMITTED:
        return decision(marker, AdbCommitRecoveryAction.ROLL_FORWARD,
            "raft quorum committed before store commit");
      case STORE_COMMITTED:
      case REPLIED:
        return decision(marker, AdbCommitRecoveryAction.RETURN_COMMITTED,
            "store commit is durable");
      case ROLLED_BACK:
      default:
        return decision(marker, AdbCommitRecoveryAction.DISCARD,
            "transaction already rolled back");
    }
  }

  private static AdbCommitRecoveryDecision decision(
      AdbDurableCommitMarker marker, AdbCommitRecoveryAction action,
      String reason) {
    return new AdbCommitRecoveryDecision(marker, action, reason);
  }
}
