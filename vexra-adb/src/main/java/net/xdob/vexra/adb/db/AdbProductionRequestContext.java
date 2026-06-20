package net.xdob.vexra.adb.db;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * ADB 生产能力校验的请求上下文。
 *
 * <p>该对象只携带 guard 判断需要的最小信息：请求名、路由 epoch 和命中的
 * region 集合。它不持有事务对象或 store 资源，因此可以被 SQL、事务和运维路径复用。</p>
 */
public final class AdbProductionRequestContext {
  private final String requestName;
  private final long routeEpoch;
  private final List<String> regionIds;

  /**
   * 创建生产校验请求上下文。
   *
   * @param requestName 请求名称，用于错误消息和诊断
   * @param routeEpoch 请求使用的路由 epoch，未知时可传 -1
   * @param regionIds 请求命中的 region id 集合
   */
  public AdbProductionRequestContext(String requestName, long routeEpoch,
      Collection<String> regionIds) {
    this.requestName = normalize(requestName, "requestName");
    this.routeEpoch = routeEpoch;
    if (regionIds == null || regionIds.isEmpty()) {
      this.regionIds = Collections.emptyList();
    } else {
      List<String> copy = new ArrayList<>();
      for (String regionId : regionIds) {
        copy.add(normalize(regionId, "regionId"));
      }
      this.regionIds = Collections.unmodifiableList(copy);
    }
  }

  /**
   * 创建不依赖 region 路由的本地请求上下文。
   *
   * @param requestName 请求名称
   * @return 本地请求上下文
   */
  public static AdbProductionRequestContext local(String requestName) {
    return new AdbProductionRequestContext(requestName, -1,
        Collections.<String>emptyList());
  }

  public String getRequestName() {
    return requestName;
  }

  public long getRouteEpoch() {
    return routeEpoch;
  }

  public List<String> getRegionIds() {
    return regionIds;
  }

  private static String normalize(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is empty");
    }
    return value.trim();
  }
}
