
package net.xdob.vexra.metrics.impl;

import net.xdob.vexra.metrics.VexraMetricRegistry;
import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.ScheduledReporter;
import com.codahale.metrics.jmx.JmxReporter;
import net.xdob.vexra.util.TimeDuration;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class MetricsReporting {
  private MetricsReporting() {
  }

  static Consumer<VexraMetricRegistry> consoleReporter(TimeDuration rate) {
    return registry -> consoleReporter(rate, registry);
  }

  private static void consoleReporter(TimeDuration rate, VexraMetricRegistry registry) {
    registry.wrap(DropWizardMetricSupport.class)
        .ifPresent(impl -> {
          final ConsoleReporter reporter = ConsoleReporter.forRegistry(impl.getDropWizardMetricRegistry())
              .convertRatesTo(TimeUnit.SECONDS)
              .convertDurationsTo(TimeUnit.MILLISECONDS)
              .build();
          reporter.start(rate.getDuration(), rate.getUnit());
          impl.setConsoleReporter(reporter);
        });

  }

  static Consumer<VexraMetricRegistry> stopConsoleReporter() {
    return MetricsReporting::stopConsoleReporter;
  }

  private static void stopConsoleReporter(VexraMetricRegistry registry) {
    registry.wrap(DropWizardMetricSupport.class)
        .map(DropWizardMetricSupport::getConsoleReporter)
        .ifPresent(ScheduledReporter::close);
  }

  static Consumer<VexraMetricRegistry> jmxReporter() {
    return MetricsReporting::jmxReporter;
  }

  private static void jmxReporter(VexraMetricRegistry registry) {
    registry.wrap(DropWizardMetricSupport.class)
        .ifPresent(impl -> {
          final JmxReporter reporter = JmxReporter.forRegistry(impl.getDropWizardMetricRegistry())
              .inDomain(registry.getMetricRegistryInfo().getApplicationName())
              .createsObjectNamesWith(new VexraObjectNameFactory())
              .build();
          reporter.start();
          impl.setJmxReporter(reporter);
        });
  }

  static Consumer<VexraMetricRegistry> stopJmxReporter() {
    return MetricsReporting::stopJmxReporter;
  }

  private static void stopJmxReporter(VexraMetricRegistry registry) {
    registry.wrap(DropWizardMetricSupport.class)
        .map(DropWizardMetricSupport::getJmxReporter)
        .ifPresent(JmxReporter::close);
  }
}
