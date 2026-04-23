package net.xdob.vexra.server;

import net.xdob.vexra.protocol.BeanTarget;

@FunctionalInterface
public interface BeanFinder {
  <T> T getBean(BeanTarget<T> target);
}
