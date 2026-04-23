package net.xdob.vexra.server.impl;

import net.xdob.vexra.metrics.MetricRegistryInfo;
import net.xdob.vexra.metrics.VexraMetricRegistry;
import net.xdob.vexra.metrics.VexraMetrics;
import net.xdob.vexra.metrics.Timekeeper;
import net.xdob.vexra.server.raftlog.RaftLogIndex;
import net.xdob.vexra.statemachine.StateMachine;

import java.util.function.LongSupplier;

/**
 * Metrics Registry for the State Machine Updater. One instance per group.
 */
public final class StateMachineMetrics extends VexraMetrics {

  public static final String VEXRA_STATEMACHINE_METRICS = "state_machine";
  public static final String VEXRA_STATEMACHINE_METRICS_DESC = "Metrics for State Machine Updater";

  public static final String STATEMACHINE_APPLIED_INDEX_GAUGE = "appliedIndex";
  public static final String STATEMACHINE_APPLY_COMPLETED_GAUGE = "applyCompletedIndex";
  public static final String STATEMACHINE_TAKE_SNAPSHOT_TIMER = "takeSnapshot";

  public static StateMachineMetrics getStateMachineMetrics(
      RaftServerImpl server, RaftLogIndex appliedIndex,
      StateMachine stateMachine) {

    String serverId = server.getMemberId().toString();
    LongSupplier getApplied = appliedIndex::get;
    LongSupplier getApplyCompleted =
        () -> (stateMachine.getLastAppliedTermIndex() == null) ? -1
            : stateMachine.getLastAppliedTermIndex().getIndex();

    return new StateMachineMetrics(serverId, getApplied, getApplyCompleted);
  }

  private final Timekeeper takeSnapshotTimer = getRegistry().timer(STATEMACHINE_TAKE_SNAPSHOT_TIMER);

  private StateMachineMetrics(String serverId, LongSupplier getApplied,
      LongSupplier getApplyCompleted) {
    super(createRegistry(serverId));

    getRegistry().gauge(STATEMACHINE_APPLIED_INDEX_GAUGE, () -> getApplied::getAsLong);
    getRegistry().gauge(STATEMACHINE_APPLY_COMPLETED_GAUGE, () -> getApplyCompleted::getAsLong);
  }

  private static VexraMetricRegistry createRegistry(String serverId) {
    return create(new MetricRegistryInfo(serverId,
        VEXRA_APPLICATION_NAME_METRICS,
        VEXRA_STATEMACHINE_METRICS, VEXRA_STATEMACHINE_METRICS_DESC));
  }

  public Timekeeper getTakeSnapshotTimer() {
    return takeSnapshotTimer;
  }

}