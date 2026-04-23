
package net.xdob.vexra.client.api;

import net.xdob.vexra.io.CloseAsync;
import net.xdob.vexra.protocol.Message;
import net.xdob.vexra.protocol.RaftClientReply;

import java.util.concurrent.CompletableFuture;

/** Stream {@link Message}(s) asynchronously. */
public interface MessageOutputStream extends CloseAsync<RaftClientReply> {
  /**
   * Send asynchronously the given message to this stream.
   *
   * If end-of-request is true, this message is the last message of the request.
   * All the messages accumulated are considered as a single request.
   *
   * @param message the message to be sent.
   * @param endOfRequest Is this an end-of-request?
   * @return a future of the reply.
   */
  CompletableFuture<RaftClientReply> sendAsync(Message message, boolean endOfRequest);

  /** The same as sendAsync(message, false). */
  default CompletableFuture<RaftClientReply> sendAsync(Message message) {
    return sendAsync(message, false);
  }
}
