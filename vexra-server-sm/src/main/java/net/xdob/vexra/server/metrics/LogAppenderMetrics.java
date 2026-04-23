package net.xdob.vexra.server.metrics;

import net.xdob.vexra.metrics.MetricRegistryInfo;
import net.xdob.vexra.metrics.VexraMetricRegistry;
import net.xdob.vexra.metrics.VexraMetrics;
import net.xdob.vexra.protocol.RaftGroupMemberId;
import net.xdob.vexra.protocol.RaftPeerId;
import net.xdob.vexra.util.Timestamp;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class LogAppenderMetrics extends VexraMetrics {
  public static final String VEXRA_LOG_APPENDER_METRICS = "log_appender";
  public static final String VEXRA_LOG_APPENDER_METRICS_DESC = "Metrics for log appender";

  public static final String FOLLOWER_NEXT_INDEX = "follower_%s_next_index";
  public static final String FOLLOWER_MATCH_INDEX = "follower_%s_match_index";
  public static final String FOLLOWER_RPC_RESP_TIME = "follower_%s_rpc_response_time";

  public LogAppenderMetrics(RaftGroupMemberId groupMemberId) {
    super(createRegistry(groupMemberId.toString()));
  }

  private static VexraMetricRegistry createRegistry(String serverId) {
    return create(new MetricRegistryInfo(serverId,
        VEXRA_APPLICATION_NAME_METRICS,
        VEXRA_LOG_APPENDER_METRICS, VEXRA_LOG_APPENDER_METRICS_DESC));
  }

  public void addFollowerGauges(RaftPeerId id, LongSupplier getNextIndex, LongSupplier getMatchIndex,
      Supplier<Timestamp> getLastRpcTime) {
    getRegistry().gauge(String.format(FOLLOWER_NEXT_INDEX, id), () -> getNextIndex::getAsLong);
    getRegistry().gauge(String.format(FOLLOWER_MATCH_INDEX, id), () -> getMatchIndex::getAsLong);
    getRegistry().gauge(String.format(FOLLOWER_RPC_RESP_TIME, id), () -> () -> getLastRpcTime.get().elapsedTimeMs());
  }
}
