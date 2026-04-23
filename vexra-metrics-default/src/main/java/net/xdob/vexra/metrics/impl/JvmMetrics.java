package net.xdob.vexra.metrics.impl;

import net.xdob.vexra.metrics.MetricRegistries;
import net.xdob.vexra.metrics.MetricRegistryInfo;
import net.xdob.vexra.metrics.VexraMetricRegistry;
import com.codahale.metrics.jvm.ClassLoadingGaugeSet;
import com.codahale.metrics.jvm.GarbageCollectorMetricSet;
import com.codahale.metrics.jvm.MemoryUsageGaugeSet;
import com.codahale.metrics.jvm.ThreadStatesGaugeSet;
import net.xdob.vexra.util.TimeDuration;

/**
 * Helper class to add JVM metrics.
 */
public interface JvmMetrics {
  static void initJvmMetrics(TimeDuration consoleReportRate) {
    final MetricRegistries registries = MetricRegistries.global();
    JvmMetrics.addJvmMetrics(registries);
    registries.enableConsoleReporter(consoleReportRate);
    registries.enableJmxReporter();
  }

  static void addJvmMetrics(MetricRegistries registries) {
    MetricRegistryInfo info = new MetricRegistryInfo("jvm", "vexra_jvm", "jvm", "jvm metrics");

    VexraMetricRegistry registry = registries.create(info);

    registry.wrap(DropWizardMetricSupport.class).ifPresent(e->{
      e.registerAll("gc", new GarbageCollectorMetricSet());
      e.registerAll("memory", new MemoryUsageGaugeSet());
      e.registerAll("threads", new ThreadStatesGaugeSet());
      e.registerAll("classLoading", new ClassLoadingGaugeSet());
    });

  }
}
