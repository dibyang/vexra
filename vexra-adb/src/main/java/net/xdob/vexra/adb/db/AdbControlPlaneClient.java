package net.xdob.vexra.adb.db;

/**
 * ADB 控制面客户端。
 *
 * <p>该接口代表 ADB runtime 访问 PD-like 控制面的最小边界：获取 region 路由快照
 * 和分配全局时间戳。真实控制面、测试 fake 和单进程内存实现都应实现该接口。</p>
 */
public interface AdbControlPlaneClient {
  /**
   * 获取当前 region 路由快照。
   *
   * @return region 路由快照
   */
  AdbControlPlaneSnapshot getSnapshot();

  /**
   * 分配下一个全局时间戳。
   *
   * @return 全局单调时间戳
   */
  long nextTimestamp();

  /**
   * 观察 route epoch 是否已经超过调用方持有的快照。
   *
   * <p>默认实现读取一次完整快照，真实控制面可以覆盖为轻量 watch 或长轮询。
   * 该方法不改变控制面状态，只返回一次观察结果。</p>
   *
   * @param lastSeenEpoch 调用方已看到的 route epoch
   * @return route watch 观察结果
   */
  default AdbRouteWatch watchRoutes(long lastSeenEpoch) {
    AdbControlPlaneSnapshot snapshot = getSnapshot();
    return new AdbRouteWatch(lastSeenEpoch, snapshot.getRouteEpoch(),
        snapshot.getRouteEpoch() > lastSeenEpoch,
        System.currentTimeMillis());
  }
}
