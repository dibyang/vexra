
package net.xdob.vexra.client;

import net.xdob.vexra.client.api.AsyncApi;
import net.xdob.vexra.protocol.RaftClientReply;
import net.xdob.vexra.protocol.RaftClientRequest;

import java.util.concurrent.CompletableFuture;

/** An RPC interface which extends the user interface {@link AsyncApi}. */
public interface AsyncRpcApi extends AsyncApi {
  /**
   * Send the given forward-request asynchronously to the raft service.
   *
   * @param request The request to be forwarded.
   * @return a future of the reply.
   */
  CompletableFuture<RaftClientReply> sendForward(RaftClientRequest request);
}
