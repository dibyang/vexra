

package net.xdob.vexra.client;

import net.xdob.vexra.client.api.DataStreamApi;
import net.xdob.vexra.client.api.DataStreamOutput;
import net.xdob.vexra.protocol.RaftClientRequest;

/** An RPC interface which extends the user interface {@link DataStreamApi}. */
public interface DataStreamRpcApi extends DataStreamApi {
  /** Create a stream for primary server to send data to peer server. */
  DataStreamOutput stream(RaftClientRequest request);
}
