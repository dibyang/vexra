package net.xdob.vexra.adb.db;

import java.util.Objects;

/**
 * ADB commit marker 恢复决策。
 */
public final class AdbCommitRecoveryDecision {
  private final AdbDurableCommitMarker marker;
  private final AdbCommitRecoveryAction action;
  private final String reason;

  public AdbCommitRecoveryDecision(AdbDurableCommitMarker marker,
      AdbCommitRecoveryAction action, String reason) {
    this.marker = Objects.requireNonNull(marker, "marker == null");
    this.action = Objects.requireNonNull(action, "action == null");
    this.reason = reason == null ? "" : reason.trim();
  }

  public AdbDurableCommitMarker getMarker() {
    return marker;
  }

  public AdbCommitRecoveryAction getAction() {
    return action;
  }

  public String getReason() {
    return reason;
  }
}
