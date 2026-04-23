package net.xdob.vexra.server.metrics;

import net.xdob.vexra.metrics.LongCounter;
import net.xdob.vexra.metrics.MetricRegistryInfo;
import net.xdob.vexra.metrics.VexraMetricRegistry;
import net.xdob.vexra.metrics.VexraMetrics;
import net.xdob.vexra.metrics.Timekeeper;
import net.xdob.vexra.protocol.RaftGroupMemberId;
import net.xdob.vexra.util.Timestamp;

import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Class to update the metrics related to Leader Election.
 */
public final class LeaderElectionMetrics extends VexraMetrics {

  public static final String VEXRA_LEADER_ELECTION_METRICS = "leader_election";
  public static final String VEXRA_LEADER_ELECTION_METRICS_DESC = "Metrics for Vexra Leader Election.";

  public static final String LEADER_ELECTION_COUNT_METRIC = "electionCount";
  public static final String LEADER_ELECTION_TIMEOUT_COUNT_METRIC = "timeoutCount";
  public static final String LEADER_ELECTION_TIME_TAKEN = "electionTime";
  public static final String LAST_LEADER_ELAPSED_TIME = "lastLeaderElapsedTime";
  public static final String TRANSFER_LEADERSHIP_COUNT_METRIC = "transferLeadershipCount";

  public static final String LAST_LEADER_ELECTION_ELAPSED_TIME = "lastLeaderElectionElapsedTime";

  private final LongCounter electionCount = getRegistry().counter(LEADER_ELECTION_COUNT_METRIC);
  private final LongCounter timeoutCount = getRegistry().counter(LEADER_ELECTION_TIMEOUT_COUNT_METRIC);
  private final LongCounter transferLeadershipCount = getRegistry().counter(TRANSFER_LEADERSHIP_COUNT_METRIC);

  private final Timekeeper electionTime = getRegistry().timer(LEADER_ELECTION_TIME_TAKEN);

  @SuppressWarnings({"squid:S3077"}) // Suppress volatile for generic type
  private volatile Timestamp lastElectionTime;

  private LeaderElectionMetrics(RaftGroupMemberId serverId, LongSupplier getLastLeaderElapsedTimeMs) {
    super(createRegistry(serverId));

    getRegistry().gauge(LAST_LEADER_ELAPSED_TIME, () -> getLastLeaderElapsedTimeMs::getAsLong);
    getRegistry().gauge(LAST_LEADER_ELECTION_ELAPSED_TIME,
        () -> () -> Optional.ofNullable(lastElectionTime).map(Timestamp::elapsedTimeMs).orElse(-1L));
  }

  public static VexraMetricRegistry createRegistry(RaftGroupMemberId serverId) {
    return create(new MetricRegistryInfo(serverId.toString(),
        VEXRA_APPLICATION_NAME_METRICS, VEXRA_LEADER_ELECTION_METRICS,
        VEXRA_LEADER_ELECTION_METRICS_DESC));
  }

  public static LeaderElectionMetrics getLeaderElectionMetrics(
      RaftGroupMemberId serverId, LongSupplier getLastLeaderElapsedTimeMs) {
    return new LeaderElectionMetrics(serverId, getLastLeaderElapsedTimeMs);
  }

  public void onNewLeaderElectionCompletion() {
    electionCount.inc();
    lastElectionTime = Timestamp.currentTime();
  }

  public Timekeeper getLeaderElectionTimer() {
    return electionTime;
  }

  public void onLeaderElectionTimeout() {
    timeoutCount.inc();
  }

  public void onTransferLeadership() {
    transferLeadershipCount.inc();
  }
}
