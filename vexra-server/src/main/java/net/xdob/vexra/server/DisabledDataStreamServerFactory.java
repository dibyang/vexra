package net.xdob.vexra.server;

import net.xdob.vexra.conf.Parameters;
import net.xdob.vexra.datastream.SupportedDataStreamType;
import net.xdob.vexra.protocol.RaftPeer;

import java.net.InetSocketAddress;
import java.util.Collection;

/** A stream factory that does nothing when data stream is disabled. */
public class DisabledDataStreamServerFactory implements DataStreamServerFactory {
  public DisabledDataStreamServerFactory(Parameters parameters) {}

  @Override
  public DataStreamServerRpc newDataStreamServerRpc(RaftServer server) {
    return new DataStreamServerRpc() {
      @Override
      public void start() {}

      @Override
      public InetSocketAddress getInetSocketAddress() {
        return null;
      }

      @Override
      public void close() {}

      @Override
      public void addRaftPeers(Collection<RaftPeer> peers) {}
    };
  }

  @Override
  public SupportedDataStreamType getDataStreamType() {
    return SupportedDataStreamType.DISABLED;
  }
}
