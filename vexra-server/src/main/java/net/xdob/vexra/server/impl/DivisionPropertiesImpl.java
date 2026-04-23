package net.xdob.vexra.server.impl;

import net.xdob.vexra.conf.RaftProperties;
import net.xdob.vexra.server.DivisionProperties;
import net.xdob.vexra.server.config.RaftServerConfigKeys;
import net.xdob.vexra.util.Preconditions;
import net.xdob.vexra.util.TimeDuration;

class DivisionPropertiesImpl implements DivisionProperties {
  private final TimeDuration rpcTimeoutMin;
  private final TimeDuration rpcTimeoutMax;
  private final TimeDuration rpcSleepTime;
  private final TimeDuration rpcSlownessTimeout;

  DivisionPropertiesImpl(RaftProperties properties) {
    this.rpcTimeoutMin = RaftServerConfigKeys.Rpc.timeoutMin(properties);
    this.rpcTimeoutMax = RaftServerConfigKeys.Rpc.timeoutMax(properties);
    Preconditions.assertTrue(rpcTimeoutMax.compareTo(rpcTimeoutMin) >= 0,
        "rpcTimeoutMax = %s < rpcTimeoutMin = %s", rpcTimeoutMax, rpcTimeoutMin);

    this.rpcSleepTime = RaftServerConfigKeys.Rpc.sleepTime(properties);
    this.rpcSlownessTimeout = RaftServerConfigKeys.Rpc.slownessTimeout(properties);
  }

  @Override
  public TimeDuration minRpcTimeout() {
    return rpcTimeoutMin;
  }

  @Override
  public TimeDuration maxRpcTimeout() {
    return rpcTimeoutMax;
  }

  @Override
  public TimeDuration rpcSleepTime() {
    return rpcSleepTime;
  }

  @Override
  public TimeDuration rpcSlownessTimeout() {
    return rpcSlownessTimeout;
  }
}