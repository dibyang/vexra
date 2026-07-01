package net.xdob.vexra.adb.ha2;

import java.sql.SQLException;

/**
 * ADB RClient 工厂。
 *
 * <p>该接口把部署层的地址发现、认证、TLS、连接池和重试策略隔离在
 * `vexra-adb` 运行时之外。控制面快照目前只包含 replicaId/leaderId，
 * 不包含真实网络地址，因此刷新器只能通过该工厂按 replicaId 获取
 * 可用的 {@link RClient}。</p>
 */
@FunctionalInterface
public interface AdbRClientFactory {

  /**
   * 为指定 replica 创建或获取 RClient。
   *
   * @param replicaId 副本或 leader 标识
   * @return 可用于该 replica 的 RClient
   * @throws SQLException 创建连接或解析部署信息失败时抛出
   */
  RClient create(String replicaId) throws SQLException;
}
