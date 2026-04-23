
package net.xdob.vexra.client;

import net.xdob.vexra.conf.Parameters;
import net.xdob.vexra.conf.RaftProperties;
import net.xdob.vexra.datastream.SupportedDataStreamType;
import net.xdob.vexra.protocol.RaftPeer;

/** A stream factory that does nothing when data stream is disabled. */
public class DisabledDataStreamClientFactory implements DataStreamClientFactory {
  public DisabledDataStreamClientFactory(Parameters parameters) {}

  @Override
  public SupportedDataStreamType getDataStreamType() {
    return SupportedDataStreamType.DISABLED;
  }

  @Override
  public DataStreamClientRpc newDataStreamClientRpc(RaftPeer server, RaftProperties properties) {
    return new DataStreamClientRpc() {
      @Override
      public void close() {}
    };
  }
}
