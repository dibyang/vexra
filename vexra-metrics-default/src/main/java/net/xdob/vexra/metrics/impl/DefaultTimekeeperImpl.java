package net.xdob.vexra.metrics.impl;

import net.xdob.vexra.metrics.Timekeeper;
import com.codahale.metrics.Timer;

/**
 * The default implementation of {@link Timekeeper} by the shaded {@link Timer}.
 */
public class DefaultTimekeeperImpl implements Timekeeper {
  private final Timer timer;

  DefaultTimekeeperImpl(Timer timer) {
    this.timer = timer;
  }

  public Timer getTimer() {
    return timer;
  }

  @Override
  public Context time() {
    final Timer.Context context = timer.time();
    return context::stop;
  }
}
