package net.xdob.vexra.adb.ha2;

import net.xdob.vexra.RaftConfigKeys;
import net.xdob.vexra.adb.AdbStateMachine;
import net.xdob.vexra.adb.db.AdbRegionCommitRequest;
import net.xdob.vexra.adb.db.AdbRegionMutation;
import net.xdob.vexra.adb.db.AdbRegionScanRequest;
import net.xdob.vexra.adb.db.AdbRpcRegionCommitClient;
import net.xdob.vexra.adb.db.Meta;
import net.xdob.vexra.adb.db.RowCodec;
import net.xdob.vexra.adb.db.RowValue;
import net.xdob.vexra.adb.key.DataKey;
import net.xdob.vexra.adb.key.RowKey;
import net.xdob.vexra.adb.key.TabId;
import net.xdob.vexra.cluster.region.KeyRange;
import net.xdob.vexra.cluster.sql.RegionQueryResult;
import net.xdob.vexra.cluster.sql.RegionScanTask;
import net.xdob.vexra.conf.Parameters;
import net.xdob.vexra.conf.RaftProperties;
import net.xdob.vexra.grpc.GrpcConfigKeys;
import net.xdob.vexra.protocol.RaftGroup;
import net.xdob.vexra.protocol.RaftGroupId;
import net.xdob.vexra.protocol.RaftPeer;
import net.xdob.vexra.rpc.SupportedRpcType;
import net.xdob.vexra.server.RaftServer;
import net.xdob.vexra.server.config.RaftServerConfigKeys;
import org.h2.value.ValueVarchar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ADB 真实 Raft/GRPC region RPC smoke 测试。
 *
 * <p>该测试在同一 JVM 内启动 3 个真实 {@link RaftServer}，每个节点使用独立
 * GRPC 端口、storage 目录和 cache 目录，再通过 {@link RaftRClient} 发送 ADB
 * proto。它验证 ADB-Prod-01 的多节点 Raft/RPC 协议链路，但不替代 OS
 * 多进程部署脚本验收。</p>
 */
class AdbRealRaftRegionRpcSmokeTest {
  @TempDir
  private Path tempDir;

  /**
   * 验证真实 3 节点 Raft/GRPC 集群可以完成 prewrite、commit 和 region scan。
   */
  @Test
  void shouldCommitAndScanVisibleRowThroughRealRaftGrpcCluster()
      throws Exception {
    int[] ports = findFreePorts(3);
    RaftGroupId groupId = RaftGroupId.randomId();
    List<RaftPeer> peers = peers(ports);
    RaftGroup group = RaftGroup.valueOf(groupId, peers);
    List<RaftServer> servers = new ArrayList<>();

    try {
      for (RaftPeer peer : peers) {
        RaftServer server = newServer(group, peer, portOf(peer));
        servers.add(server);
        server.start();
      }

      try (RaftRClient rClient = new RaftRClient(clientProperties(groupId,
          peers));
           AdbRpcRegionCommitClient commitClient =
               new AdbRpcRegionCommitClient(
                   new AdbRaftRegionCommitTransport("adb", rClient),
                   TimeUnit.SECONDS.toMillis(30))) {
        commitAndScanEventually(commitClient,
            new AdbRaftRegionScanClient("adb", rClient));
      }
    } finally {
      closeReverse(servers);
    }
  }

  private static void commitAndScanEventually(
      AdbRpcRegionCommitClient commitClient, AdbRaftRegionScanClient scanClient)
      throws Exception {
    Exception last = null;
    for (int attempt = 0; attempt < 12; attempt++) {
      try {
        commitAndScanOnce(commitClient, scanClient, attempt + 1);
        return;
      } catch (Exception e) {
        last = e;
        Thread.sleep(500L);
      }
    }
    throw last;
  }

  private static void commitAndScanOnce(AdbRpcRegionCommitClient commitClient,
      AdbRaftRegionScanClient scanClient, long rowId) throws Exception {
    long txnId = 10 + rowId;
    long commitTs = 20 + rowId;
    RowKey rowKey = rowKey(rowId);
    AdbRegionCommitRequest request = new AdbRegionCommitRequest(
        "r1", 1, "node-a", txnId, txnId, commitTs, "r1", rowKey, 3000, true,
        Collections.singletonList((DataKey) rowKey),
        Collections.singletonList(new AdbRegionMutation(rowKey,
            rowValue(txnId, "raft-grpc-smoke"))),
        Collections.<Meta>emptyList());

    commitClient.prewriteAsync(request).get(30, TimeUnit.SECONDS);
    commitClient.commitAsync(request).get(30, TimeUnit.SECONDS);

    RegionQueryResult result = scanClient.scanAsync(scanRequest(commitTs,
        rowId)).get(30, TimeUnit.SECONDS);

    assertEquals(1, result.getRows().size());
    assertEquals("raft-grpc-smoke", result.getRows().get(0).get("payload"));
  }

  private RaftServer newServer(RaftGroup group, RaftPeer peer, int port)
      throws IOException {
    RaftProperties properties = new RaftProperties();
    RaftConfigKeys.Rpc.setType(properties, SupportedRpcType.GRPC);
    GrpcConfigKeys.Server.setHost(properties, "127.0.0.1");
    GrpcConfigKeys.Server.setPort(properties, port);
    RaftServerConfigKeys.setStorageDir(properties,
        Collections.singletonList(tempDir.resolve("storage")
            .resolve(peer.getId().getId()).toFile()));
    RaftServerConfigKeys.setCacheDir(properties, tempDir.resolve("cache")
        .resolve(peer.getId().getId()).toFile());

    return RaftServer.newBuilder()
        .setServerId(peer.getId())
        .setGroup(group)
        .setProperties(properties)
        .setParameters(new Parameters())
        .setStateMachineRegistry(gid -> new AdbStateMachine(gid,
            peer.getId()))
        .build();
  }

  private static List<RaftPeer> peers(int[] ports) {
    List<RaftPeer> peers = new ArrayList<>();
    for (int i = 0; i < ports.length; i++) {
      peers.add(RaftPeer.newBuilder()
          .setId("n" + (i + 1))
          .setAddress("127.0.0.1", ports[i])
          .build());
    }
    return peers;
  }

  private static Properties clientProperties(RaftGroupId groupId,
      List<RaftPeer> peers) {
    Properties properties = new Properties();
    properties.setProperty("HA2.GROUP", groupId.toString());
    properties.setProperty("HA2.NODES", nodes(peers));
    return properties;
  }

  private static String nodes(List<RaftPeer> peers) {
    StringBuilder builder = new StringBuilder();
    for (RaftPeer peer : peers) {
      if (builder.length() > 0) {
        builder.append(',');
      }
      builder.append(peer.getId().getId()).append('@').append(peer.getAddress());
    }
    return builder.toString();
  }

  private static int portOf(RaftPeer peer) {
    String address = peer.getAddress();
    return Integer.parseInt(address.substring(address.lastIndexOf(':') + 1));
  }

  private static void closeReverse(List<RaftServer> servers) {
    for (int i = servers.size() - 1; i >= 0; i--) {
      try {
        servers.get(i).close();
      } catch (IOException ignored) {
        // 测试清理阶段尽量关闭所有节点，避免前一个关闭异常掩盖后续泄漏。
      }
    }
  }

  private static int[] findFreePorts(int count) throws IOException {
    ServerSocket[] sockets = new ServerSocket[count];
    try {
      int[] ports = new int[count];
      for (int i = 0; i < count; i++) {
        sockets[i] = new ServerSocket(0);
        ports[i] = sockets[i].getLocalPort();
      }
      return ports;
    } finally {
      for (ServerSocket socket : sockets) {
        if (socket != null) {
          socket.close();
        }
      }
    }
  }

  private static AdbRegionScanRequest scanRequest(long readTs, long rowId) {
    return new AdbRegionScanRequest(new RegionScanTask("r1",
        new KeyRange(rowKey(rowId).toBytes(), rowKey(rowId + 1).toBytes()),
        Collections.emptyList(), Collections.emptyList(), 0, readTs),
        7, readTs, false, 0);
  }

  private static RowValue rowValue(long txnId, String value) {
    RowValue rowValue = new RowValue();
    rowValue.txnId = txnId;
    rowValue.payload = RowCodec.encode(ValueVarchar.get(value));
    return rowValue;
  }

  private static RowKey rowKey(long rowId) {
    return RowKey.of(TabId.of(1, 0L), rowId);
  }
}
