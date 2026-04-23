package net.xdob.vexra.server;

import net.xdob.vexra.protocol.BeanTarget;

import java.util.Optional;

public interface BeansFactory {
  <T> Optional<T> getBean(BeanTarget<T> target);
  void addBeanFinder(BeanFinder beanFinder);
  void removeBeanFinder(BeanFinder beanFinder);
}
