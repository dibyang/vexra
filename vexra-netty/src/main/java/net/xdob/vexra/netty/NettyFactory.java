
package net.xdob.vexra.netty;

import net.xdob.vexra.client.ClientFactory;
import net.xdob.vexra.conf.Parameters;
import net.xdob.vexra.conf.RaftProperties;
import net.xdob.vexra.netty.client.NettyClientRpc;
import net.xdob.vexra.netty.server.NettyRpcService;
import net.xdob.vexra.protocol.ClientId;
import net.xdob.vexra.rpc.SupportedRpcType;
import net.xdob.vexra.server.RaftServer;
import net.xdob.vexra.server.ServerFactory;

public class NettyFactory implements ServerFactory, ClientFactory {
  public NettyFactory(Parameters parameters) {}

  @Override
  public SupportedRpcType getRpcType() {
    return SupportedRpcType.NETTY;
  }

  @Override
  public NettyRpcService newRaftServerRpc(RaftServer server) {
    return NettyRpcService.newBuilder().setServer(server).build();
  }

  @Override
  public NettyClientRpc newRaftClientRpc(ClientId clientId, RaftProperties properties) {
    return new NettyClientRpc(clientId, properties);
  }
}
