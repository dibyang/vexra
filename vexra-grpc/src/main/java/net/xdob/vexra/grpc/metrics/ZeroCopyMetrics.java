package net.xdob.vexra.grpc.metrics;

import net.xdob.vexra.metrics.LongCounter;
import net.xdob.vexra.metrics.MetricRegistryInfo;
import net.xdob.vexra.metrics.VexraMetricRegistry;
import net.xdob.vexra.metrics.VexraMetrics;
import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.AbstractMessage;

import java.util.function.Supplier;

public class ZeroCopyMetrics extends VexraMetrics {
  private static final String VEXRA_GRPC_METRICS_APP_NAME = "vexra_grpc";
  private static final String VEXRA_GRPC_METRICS_COMP_NAME = "zero_copy";
  private static final String VEXRA_GRPC_METRICS_DESC = "Metrics for Vexra Grpc Zero copy";

  private final LongCounter zeroCopyMessages = getRegistry().counter("num_zero_copy_messages");
  private final LongCounter nonZeroCopyMessages = getRegistry().counter("num_non_zero_copy_messages");
  private final LongCounter releasedMessages = getRegistry().counter("num_released_messages");

  public ZeroCopyMetrics() {
    super(createRegistry());
  }

  private static VexraMetricRegistry createRegistry() {
    return create(new MetricRegistryInfo("",
        VEXRA_GRPC_METRICS_APP_NAME,
        VEXRA_GRPC_METRICS_COMP_NAME, VEXRA_GRPC_METRICS_DESC));
  }

  public void addUnreleased(String name, Supplier<Integer> unreleased) {
    getRegistry().gauge(name +  "_num_unreleased_messages", () -> unreleased);
  }


  public void onZeroCopyMessage(AbstractMessage ignored) {
    zeroCopyMessages.inc();
  }

  public void onNonZeroCopyMessage(AbstractMessage ignored) {
    nonZeroCopyMessages.inc();
  }

  public void onReleasedMessage(AbstractMessage ignored) {
    releasedMessages.inc();
  }

  @VisibleForTesting
  public long zeroCopyMessages() {
    return zeroCopyMessages.getCount();
  }

  @VisibleForTesting
  public long nonZeroCopyMessages() {
    return nonZeroCopyMessages.getCount();
  }

  @VisibleForTesting
  public long releasedMessages() {
    return releasedMessages.getCount();
  }
}