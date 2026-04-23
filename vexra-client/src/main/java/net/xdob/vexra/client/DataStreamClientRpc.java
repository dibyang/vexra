

package net.xdob.vexra.client;

import net.xdob.vexra.datastream.DataStreamType;
import net.xdob.vexra.protocol.DataStreamReply;
import net.xdob.vexra.protocol.DataStreamRequest;
import net.xdob.vexra.util.JavaUtils;

import java.io.Closeable;
import java.util.concurrent.CompletableFuture;

/**
 * A client interface for sending stream requests.
 * The underlying implementation is pluggable, depending on the {@link DataStreamType}.
 * The implementations of this interface define how the requests are transported to the server.
 */
public interface DataStreamClientRpc extends Closeable {
  /** Async call to send a request. */
  default CompletableFuture<DataStreamReply> streamAsync(DataStreamRequest request) {
    throw new UnsupportedOperationException(getClass() + " does not support "
        + JavaUtils.getCurrentStackTraceElement().getMethodName());
  }
}
