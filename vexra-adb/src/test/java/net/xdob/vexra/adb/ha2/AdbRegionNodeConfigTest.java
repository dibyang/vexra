package net.xdob.vexra.adb.ha2;

import net.xdob.vexra.protocol.RaftGroupId;
import net.xdob.vexra.server.RaftServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADB region node 产品启动入口测试。
 *
 * <p>测试覆盖命令行参数解析、当前节点 peer 校验和未启动 RaftServer 构造，确保部署命令可以
 * 指向 main 包入口，而不是继续依赖 test harness。</p>
 */
class AdbRegionNodeConfigTest {
  @TempDir
  File tempDir;

  /**
   * 验证 `--key value` 启动参数会被解析为完整 region node 配置。
   */
  @Test
  void shouldParseRegionNodeArguments() {
    String groupId = RaftGroupId.randomId().toString();
    AdbRegionNodeConfig config = AdbRegionNodeConfig.parse(args(groupId,
        "node-b", peers(), 19002, "storage-b", "cache-b"));

    assertEquals(groupId, config.getGroupId().toString());
    assertEquals("node-b", config.getNodeId());
    assertEquals("127.0.0.1", config.getHost());
    assertEquals(19002, config.getPort());
    assertEquals(3, config.getPeers().size());
    assertEquals("node-b", config.getSelfPeer().getId().getId());
    assertEquals(config.getGroupId(), config.raftGroup().getGroupId());
  }

  /**
   * 验证缺失必填参数或当前节点不在 peers 中时会拒绝启动配置。
   */
  @Test
  void shouldRejectMissingArgumentOrUnknownNode() {
    String groupId = RaftGroupId.randomId().toString();

    assertThrows(IllegalArgumentException.class,
        () -> AdbRegionNodeConfig.parse(new String[] {
            "--group", groupId,
            "--node", "node-a",
            "--peers", peers(),
            "--host", "127.0.0.1",
            "--storage", path("storage-a"),
            "--cache", path("cache-a")
        }));
    assertThrows(IllegalArgumentException.class,
        () -> AdbRegionNodeConfig.parse(args(groupId, "node-x", peers(),
            19001, "storage-x", "cache-x")));
  }

  /**
   * 验证 main 包入口可以基于配置构造未启动的真实 RaftServer。
   *
   * @throws Exception server 构造或关闭失败时抛出
   */
  @Test
  void shouldBuildRaftServerFromMainPackageConfig() throws Exception {
    AdbRegionNodeConfig config = AdbRegionNodeConfig.parse(args(
        RaftGroupId.randomId().toString(), "node-a",
        "node-a@127.0.0.1:19101", 19101, "storage-server",
        "cache-server"));

    try (RaftServer server = AdbRegionNodeMain.newServer(config)) {
      assertNotNull(server);
    }
  }

  private String[] args(String groupId, String nodeId, String peers, int port,
      String storageName, String cacheName) {
    return new String[] {
        "--group", groupId,
        "--node", nodeId,
        "--peers", peers,
        "--host", "127.0.0.1",
        "--port", String.valueOf(port),
        "--storage", path(storageName),
        "--cache", path(cacheName)
    };
  }

  private static String peers() {
    return "node-a@127.0.0.1:19001,node-b@127.0.0.1:19002,"
        + "witness-a@127.0.0.1:19003";
  }

  private String path(String name) {
    return new File(tempDir, name).getAbsolutePath();
  }
}
