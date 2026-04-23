package net.xdob.vexra.metrics.impl;

import net.xdob.vexra.metrics.MetricRegistryFactory;
import net.xdob.vexra.metrics.MetricRegistryInfo;
import net.xdob.vexra.metrics.VexraMetricRegistry;

public class MetricRegistryFactoryImpl implements MetricRegistryFactory {
  @Override
  public VexraMetricRegistry create(MetricRegistryInfo info) {
    return new VexraMetricRegistryImpl(info);
  }
}
