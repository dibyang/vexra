package net.xdob.vexra.adb.ha2;

import net.xdob.vexra.protocol.RaftPeer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * RaftRClient 配置解析测试。
 *
 * <p>该测试覆盖 ADB-Prod-01 多节点 smoke 需要的节点地址格式，确保同一机器上
 * 可以为不同 Raft 节点声明独立端口，同时保留旧的全局 `HA2.PORT` 兼容行为。</p>
 */
class RaftRClientTest {
  /**
   * 验证显式端口优先于全局默认端口。
   */
  @Test
  void shouldParsePeerAddressWithExplicitPort() {
    List<RaftPeer> peers = RaftRClient.parsePeers(
        "n1@127.0.0.1:18001,n2@127.0.0.1:18002", 7800);

    assertEquals("127.0.0.1:18001", peers.get(0).getAddress());
    assertEquals("127.0.0.1:18002", peers.get(1).getAddress());
  }

  /**
   * 验证旧格式仍使用 HA2.PORT 作为节点端口。
   */
  @Test
  void shouldUseDefaultPortWhenPeerAddressHasNoPort() {
    List<RaftPeer> peers = RaftRClient.parsePeers(
        "n1@127.0.0.1,n2@127.0.0.2", 7800);

    assertEquals("127.0.0.1:7800", peers.get(0).getAddress());
    assertEquals("127.0.0.2:7800", peers.get(1).getAddress());
  }
}
