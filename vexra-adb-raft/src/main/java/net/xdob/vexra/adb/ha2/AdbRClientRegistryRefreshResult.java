package net.xdob.vexra.adb.ha2;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * ADB RClient registry 刷新结果。
 *
 * <p>该值对象用于测试、诊断和后续 metrics 暴露，记录一次控制面快照刷新
 * 对 registry 产生的影响。它不持有真实连接，也不改变连接生命周期。</p>
 */
public final class AdbRClientRegistryRefreshResult {
  private final Set<String> activeLeaderIds;
  private final int registeredClients;
  private final int retainedClients;
  private final int unregisteredClients;
  private final int regionsWithoutLeader;

  /**
   * 创建 registry 刷新结果。
   *
   * @param activeLeaderIds 本次快照中可用的 leader id 集合
   * @param registeredClients 本次新增注册的 client 数量
   * @param retainedClients 本次沿用已有注册的 client 数量
   * @param unregisteredClients 本次移除的托管 client 数量
   * @param regionsWithoutLeader 本次快照中缺少 leader 的 region 数量
   */
  public AdbRClientRegistryRefreshResult(Set<String> activeLeaderIds,
      int registeredClients, int retainedClients, int unregisteredClients,
      int regionsWithoutLeader) {
    Objects.requireNonNull(activeLeaderIds, "activeLeaderIds == null");
    this.activeLeaderIds = Collections.unmodifiableSet(
        new LinkedHashSet<>(activeLeaderIds));
    this.registeredClients = nonNegative(registeredClients,
        "registeredClients");
    this.retainedClients = nonNegative(retainedClients, "retainedClients");
    this.unregisteredClients = nonNegative(unregisteredClients,
        "unregisteredClients");
    this.regionsWithoutLeader = nonNegative(regionsWithoutLeader,
        "regionsWithoutLeader");
  }

  public Set<String> getActiveLeaderIds() {
    return activeLeaderIds;
  }

  public int getRegisteredClients() {
    return registeredClients;
  }

  public int getRetainedClients() {
    return retainedClients;
  }

  public int getUnregisteredClients() {
    return unregisteredClients;
  }

  public int getRegionsWithoutLeader() {
    return regionsWithoutLeader;
  }

  private static int nonNegative(int value, String fieldName) {
    if (value < 0) {
      throw new IllegalArgumentException(fieldName + " is negative: "
          + value);
    }
    return value;
  }
}
