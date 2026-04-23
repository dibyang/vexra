
package net.xdob.vexra.client.impl;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

import net.xdob.vexra.client.api.BlockingApi;
import net.xdob.vexra.client.retry.ClientRetryEvent;
import net.xdob.vexra.proto.raft.RaftClientRequestProto.TypeCase;
import net.xdob.vexra.proto.raft.ReplicationLevel;
import net.xdob.vexra.protocol.*;
import net.xdob.vexra.protocol.exceptions.AlreadyClosedException;
import net.xdob.vexra.protocol.exceptions.AlreadyExistsException;
import net.xdob.vexra.protocol.exceptions.SetConfigurationException;
import net.xdob.vexra.protocol.exceptions.GroupMismatchException;
import net.xdob.vexra.protocol.exceptions.LeaderSteppingDownException;
import net.xdob.vexra.protocol.exceptions.StateMachineException;
import net.xdob.vexra.protocol.exceptions.TransferLeadershipException;
import net.xdob.vexra.retry.RetryPolicy;
import net.xdob.vexra.rpc.CallId;
import net.xdob.vexra.util.TimeDuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Blocking api implementations. */
class BlockingImpl implements BlockingApi {
  static final Logger LOG = LoggerFactory.getLogger(BlockingImpl.class);

  private final RaftClientImpl client;

  BlockingImpl(RaftClientImpl client) {
    this.client = Objects.requireNonNull(client, "client == null");
  }

  @Override
  public RaftClientReply send(Message message) throws IOException {
    return send(RaftClientRequest.writeRequestType(), message, null, null);
  }

  @Override
  public RaftClientReply send(Message message, long timeoutMs) throws IOException {
    return send(RaftClientRequest.writeRequestType(), message, null, timeoutMs);
  }

  @Override
  public RaftClientReply sendReadOnly(Message message, RaftPeerId server) throws IOException {
    return send(RaftClientRequest.readRequestType(), message, server, null);
  }

  @Override
  public RaftClientReply sendReadOnlyNonLinearizable(Message message) throws IOException {
    return send(RaftClientRequest.readRequestType(true), message, null, null);
  }

  @Override
  public RaftClientReply sendReadAfterWrite(Message message) throws IOException {
    return send(RaftClientRequest.readAfterWriteConsistentRequestType(), message, null, null);
  }

  @Override
  public RaftClientReply sendStaleRead(Message message, long minIndex, RaftPeerId server)
      throws IOException {
    return send(RaftClientRequest.staleReadRequestType(minIndex), message, server, null);
  }

  @Override
  public RaftClientReply watch(long index, ReplicationLevel replication) throws IOException {
    return send(RaftClientRequest.watchRequestType(index, replication), null, null,null);
  }

	@Override
	public RaftClientReply sendAdmin(Message message, RaftPeerId server) throws IOException {
    //return send(RaftClientRequest.adminRequestType(), message, server, null);
    try {
      return UnorderedAsync
          .send(RaftClientRequest.adminRequestType(), message, server, client)
          .get();
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

	private RaftClientReply send(RaftClientRequest.Type type, Message message, RaftPeerId server, Long timeoutMs)
      throws IOException {
    if (!type.is(TypeCase.WATCH)) {
      Objects.requireNonNull(message, "message == null");
    }

    final long callId = CallId.getAndIncrement();
    return sendRequestWithRetry(() -> client.newRaftClientRequest(server, callId, message, type, timeoutMs,null));
  }

  RaftClientReply sendRequestWithRetry(Supplier<RaftClientRequest> supplier) throws IOException {
    RaftClientImpl.PendingClientRequest pending = new RaftClientImpl.PendingClientRequest() {
      @Override
      public RaftClientRequest newRequestImpl() {
        return supplier.get();
      }
    };
    while (true) {
      final RaftClientRequest request = pending.newRequest();
      IOException ioe = null;
      try {
        final RaftClientReply reply = sendRequest(request);

        if (reply != null) {
          return client.handleReply(request, reply);
        }
      } catch (GroupMismatchException | StateMachineException | TransferLeadershipException |
               LeaderSteppingDownException | AlreadyClosedException | AlreadyExistsException |
               SetConfigurationException e) {
        throw e;
      } catch (IOException e) {
        ioe = e;
      }

      if (client.isClosed()) {
        throw new AlreadyClosedException(this + " is closed.");
      }

      final ClientRetryEvent event = pending.newClientRetryEvent(request, ioe);
      final RetryPolicy retryPolicy = client.getRetryPolicy();
      final RetryPolicy.Action action = retryPolicy.handleAttemptFailure(event);
      if (!action.shouldRetry()) {
        throw client.noMoreRetries(event);
      }

      final TimeDuration sleepTime = client.getEffectiveSleepTime(ioe, action.getSleepTime());
      try {
        sleepTime.sleep();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("retry policy=" + retryPolicy);
      }
    }
  }

  private RaftClientReply sendRequest(RaftClientRequest request) throws IOException {
    LOG.debug("{}: send {}", client.getId(), request);
    RaftClientReply reply;
    try {
      reply = client.getClientRpc().sendRequest(request);
    } catch (GroupMismatchException gme) {
      throw gme;
    } catch (IOException ioe) {
      client.handleIOException(request, ioe);
      throw ioe;
    }
    LOG.debug("{}: receive {}", client.getId(), reply);
    reply = client.handleLeaderException(request, reply);
    reply = RaftClientImpl.handleRaftException(reply, Function.identity());
    return reply;
  }
}
