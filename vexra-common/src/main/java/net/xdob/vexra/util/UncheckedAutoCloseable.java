package net.xdob.vexra.util;

/**
 * The same as {@link AutoCloseable}
 * except that the close method does not throw {@link Exception}.
 */
@FunctionalInterface
public interface UncheckedAutoCloseable extends AutoCloseable {
  @Override
  void close();
}
