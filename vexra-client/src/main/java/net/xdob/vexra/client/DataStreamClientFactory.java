

package net.xdob.vexra.client;

import net.xdob.vexra.conf.Parameters;
import net.xdob.vexra.conf.RaftProperties;
import net.xdob.vexra.datastream.DataStreamFactory;
import net.xdob.vexra.datastream.DataStreamType;
import net.xdob.vexra.protocol.RaftPeer;

/**
 * A factory to create streaming client.
 */
public interface DataStreamClientFactory extends DataStreamFactory {
  static DataStreamClientFactory newInstance(DataStreamType type, Parameters parameters) {
    final DataStreamFactory dataStreamFactory = type.newClientFactory(parameters);
    if (dataStreamFactory instanceof DataStreamClientFactory) {
      return (DataStreamClientFactory) dataStreamFactory;
    }
    throw new ClassCastException("Cannot cast " + dataStreamFactory.getClass()
        + " to " + DataStreamClientFactory.class + "; stream type is " + type);
  }

  DataStreamClientRpc newDataStreamClientRpc(RaftPeer server, RaftProperties properties);
}
