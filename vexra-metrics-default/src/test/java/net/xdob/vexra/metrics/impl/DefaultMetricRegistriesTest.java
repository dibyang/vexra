package net.xdob.vexra.metrics.impl;

import net.xdob.vexra.metrics.MetricRegistryInfo;
import net.xdob.vexra.metrics.VexraMetricRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * metrics-default 注册表引用计数回归测试。
 *
 * 测试验证同一个 MetricRegistryInfo 重复 create 时复用实例，remove 按引用计数删除，
 * reporter 注册和停止回调在生命周期内被调用。
 */
class DefaultMetricRegistriesTest {
  /**
   * 验证注册表复用、引用计数删除和 clear 行为。
   */
  @Test
  void shouldReuseRegistryAndRemoveAfterReferenceCountDropsToZero() {
    DefaultMetricRegistries registries = new DefaultMetricRegistries();
    MetricRegistryInfo info = info("raft");

    VexraMetricRegistry first = registries.create(info);
    VexraMetricRegistry second = registries.create(info);

    assertSame(first, second);
    assertTrue(registries.get(info).isPresent());
    assertFalse(registries.remove(info));
    assertTrue(registries.get(info).isPresent());
    assertTrue(registries.remove(info));
    assertFalse(registries.get(info).isPresent());

    registries.create(info);
    assertEquals(1, registries.getMetricRegistryInfos().size());
    registries.clear();
    assertTrue(registries.getMetricRegistryInfos().isEmpty());
  }

  /**
   * 验证 reporter 注册回调在 create 时执行，停止回调在 remove 时执行。
   */
  @Test
  void shouldInvokeReporterCallbacks() {
    DefaultMetricRegistries registries = new DefaultMetricRegistries();
    List<MetricRegistryInfo> started = new ArrayList<>();
    List<MetricRegistryInfo> stopped = new ArrayList<>();
    Consumer<VexraMetricRegistry> start = r -> started.add(r.getMetricRegistryInfo());
    Consumer<VexraMetricRegistry> stop = r -> stopped.add(r.getMetricRegistryInfo());
    MetricRegistryInfo info = info("server");

    registries.addReporterRegistration(start, stop);
    registries.create(info);
    assertEquals(1, started.size());
    assertEquals(info, started.get(0));

    assertTrue(registries.remove(info));
    assertEquals(1, stopped.size());
    assertEquals(info, stopped.get(0));

    registries.removeReporterRegistration(start, stop);
    registries.create(info("client"));
    assertEquals(1, started.size());
  }

  /**
   * 构造测试用注册表描述。
   */
  private static MetricRegistryInfo info(String prefix) {
    return new MetricRegistryInfo(prefix, "vexra", "component", "test metrics");
  }
}
