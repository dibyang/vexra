
package net.xdob.vexra.client;

import net.xdob.vexra.client.api.DataStreamOutput;
import net.xdob.vexra.protocol.DataStreamReply;

import java.util.concurrent.CompletableFuture;

/** An RPC interface which extends the user interface {@link DataStreamOutput}. */
public interface DataStreamOutputRpc extends DataStreamOutput {
  /** Get the future of the header request. */
  CompletableFuture<DataStreamReply> getHeaderFuture();
}
