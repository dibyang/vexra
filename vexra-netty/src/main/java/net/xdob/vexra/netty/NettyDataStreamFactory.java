
package net.xdob.vexra.netty;

import net.xdob.vexra.client.DataStreamClientFactory;
import net.xdob.vexra.client.DataStreamClientRpc;
import net.xdob.vexra.conf.Parameters;
import net.xdob.vexra.conf.RaftProperties;
import net.xdob.vexra.datastream.SupportedDataStreamType;
import net.xdob.vexra.netty.client.NettyClientStreamRpc;
import net.xdob.vexra.netty.server.NettyServerStreamRpc;
import net.xdob.vexra.protocol.RaftPeer;
import net.xdob.vexra.server.DataStreamServerFactory;
import net.xdob.vexra.server.DataStreamServerRpc;
import net.xdob.vexra.server.RaftServer;

import java.util.Optional;

public class NettyDataStreamFactory implements DataStreamServerFactory, DataStreamClientFactory {
  private final Parameters parameters;

  public NettyDataStreamFactory(Parameters parameters) {
    this.parameters = Optional.ofNullable(parameters).orElseGet(Parameters::new);
  }

  @Override
  public SupportedDataStreamType getDataStreamType() {
    return SupportedDataStreamType.NETTY;
  }

  @Override
  public DataStreamClientRpc newDataStreamClientRpc(RaftPeer server, RaftProperties properties) {
    return new NettyClientStreamRpc(server, NettyConfigKeys.DataStream.Client.tlsConf(parameters), properties);
  }

  @Override
  public DataStreamServerRpc newDataStreamServerRpc(RaftServer server) {
    return new NettyServerStreamRpc(server, parameters);
  }
}
