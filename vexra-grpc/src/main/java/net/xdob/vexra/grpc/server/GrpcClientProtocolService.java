
package net.xdob.vexra.grpc.server;

import io.grpc.stub.ServerCallStreamObserver;
import net.xdob.vexra.protocol.RaftClientAsynchronousProtocol;
import net.xdob.vexra.protocol.RaftGroupId;
import net.xdob.vexra.client.impl.ClientProtoUtils;
import net.xdob.vexra.grpc.GrpcUtil;
import net.xdob.vexra.grpc.metrics.ZeroCopyMetrics;
import net.xdob.vexra.grpc.util.ZeroCopyMessageMarshaller;
import net.xdob.vexra.protocol.*;
import net.xdob.vexra.protocol.exceptions.AlreadyClosedException;
import net.xdob.vexra.protocol.exceptions.GroupMismatchException;
import net.xdob.vexra.protocol.exceptions.RaftException;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.StreamObserver;
import net.xdob.vexra.proto.raft.RaftClientReplyProto;
import net.xdob.vexra.proto.raft.RaftClientRequestProto;
import net.xdob.vexra.proto.grpc.RaftClientProtocolServiceGrpc.RaftClientProtocolServiceImplBase;
import net.xdob.vexra.server.VexraLogTracer;
import net.xdob.vexra.util.Collections3;
import net.xdob.vexra.util.JavaUtils;
import net.xdob.vexra.util.Preconditions;
import net.xdob.vexra.util.ReferenceCountedObject;
import net.xdob.vexra.util.SlidingWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static net.xdob.vexra.grpc.GrpcUtil.addMethodWithCustomMarshaller;
import static net.xdob.vexra.proto.grpc.RaftClientProtocolServiceGrpc.getOrderedMethod;
import static net.xdob.vexra.proto.grpc.RaftClientProtocolServiceGrpc.getUnorderedMethod;

/**
 * Leader 的 GrpcClientProtocolService 接收请求并提交到日志队列
 * 非 Leader 节点会返回 suggestLeader 提示客户端重定向
 */
class GrpcClientProtocolService extends RaftClientProtocolServiceImplBase {
  private static final Logger LOG = LoggerFactory.getLogger(GrpcClientProtocolService.class);

  private static class PendingOrderedRequest implements SlidingWindow.ServerSideRequest<RaftClientReply> {
    private final ReferenceCountedObject<RaftClientRequest> requestRef;
    private final RaftClientRequest request;
    private final AtomicReference<RaftClientReply> reply = new AtomicReference<>();

    PendingOrderedRequest(ReferenceCountedObject<RaftClientRequest> requestRef) {
      this.requestRef = requestRef;
      this.request = requestRef != null ? requestRef.retain() : null;
    }

    @Override
    public void fail(Throwable t) {
      final RaftException e = Preconditions.assertInstanceOf(t, RaftException.class);
      setReply(RaftClientReply.newBuilder()
          .setRequest(request)
          .setException(e)
          .build());
    }

    @Override
    public boolean hasReply() {
      return getReply() != null || this == COMPLETED;
    }

    @Override
    public void setReply(RaftClientReply r) {
      final boolean set = reply.compareAndSet(null, r);
      Preconditions.assertTrue(set, () -> "Reply is already set: request=" +
          request.toStringShort() + ", reply=" + reply);
    }

    RaftClientReply getReply() {
      return reply.get();
    }

    ReferenceCountedObject<RaftClientRequest> getRequestRef() {
      return requestRef;
    }

    @Override
    public void release() {
      if (requestRef != null) {
        requestRef.release();
      }
    }

    @Override
    public long getSeqNum() {
      return request != null? request.getSlidingWindowEntry().getSeqNum(): Long.MAX_VALUE;
    }

    @Override
    public boolean isFirstRequest() {
      return request != null && request.getSlidingWindowEntry().getIsFirst();
    }

    @Override
    public String toString() {
      return request != null? getSeqNum() + ":" + reply: "COMPLETED";
    }
  }
  private static final PendingOrderedRequest COMPLETED = new PendingOrderedRequest(null);

  static class OrderedStreamObservers {
    private final Map<Integer, OrderedRequestStreamObserver> map = new ConcurrentHashMap<>();

    void putNew(OrderedRequestStreamObserver so) {
      Collections3.putNew(so.getId(), so, map, () -> JavaUtils.getClassSimpleName(getClass()));
    }

    void removeExisting(OrderedRequestStreamObserver so) {
      Collections3.removeExisting(so.getId(), so, map, () -> JavaUtils.getClassSimpleName(getClass()));
    }

    void closeAllExisting(RaftGroupId groupId) {
      // Iteration not synchronized:
      // Okay if an existing object is removed by another mean during the iteration since it must be already closed.
      // Also okay if a new object is added during the iteration since this method closes only the existing objects.
      for(Iterator<Map.Entry<Integer, OrderedRequestStreamObserver>> i = map.entrySet().iterator(); i.hasNext(); ) {
        final OrderedRequestStreamObserver so = i.next().getValue();
        final RaftGroupId gid = so.getGroupId();
        if (gid == null || gid.equals(groupId)) {
          so.close(null);
          i.remove();
        }
      }
    }
  }

  private final Supplier<RaftPeerId> idSupplier;
  private final RaftClientAsynchronousProtocol protocol;
  private final ExecutorService executor;

  private final OrderedStreamObservers orderedStreamObservers = new OrderedStreamObservers();
  private final boolean zeroCopyEnabled;
  private final ZeroCopyMessageMarshaller<RaftClientRequestProto> zeroCopyRequestMarshaller;

  GrpcClientProtocolService(Supplier<RaftPeerId> idSupplier, RaftClientAsynchronousProtocol protocol,
      ExecutorService executor, boolean zeroCopyEnabled, ZeroCopyMetrics zeroCopyMetrics) {
    this.idSupplier = idSupplier;
    this.protocol = protocol;
    this.executor = executor;
    this.zeroCopyEnabled = zeroCopyEnabled;
    this.zeroCopyRequestMarshaller = new ZeroCopyMessageMarshaller<>(RaftClientRequestProto.getDefaultInstance(),
        zeroCopyMetrics::onZeroCopyMessage, zeroCopyMetrics::onNonZeroCopyMessage, zeroCopyMetrics::onReleasedMessage);
    zeroCopyMetrics.addUnreleased("client_protocol", zeroCopyRequestMarshaller::getUnclosedCount);
  }

  RaftPeerId getId() {
    return idSupplier.get();
  }

  ServerServiceDefinition bindServiceWithZeroCopy() {
    ServerServiceDefinition orig = super.bindService();
    if (!zeroCopyEnabled) {
      LOG.info("{}: Zero copy is disabled.", getId());
      return orig;
    }
    ServerServiceDefinition.Builder builder = ServerServiceDefinition.builder(orig.getServiceDescriptor().getName());

    addMethodWithCustomMarshaller(orig, builder, getOrderedMethod(), zeroCopyRequestMarshaller);
    addMethodWithCustomMarshaller(orig, builder, getUnorderedMethod(), zeroCopyRequestMarshaller);

    return builder.build();
  }

  @Override
  public StreamObserver<RaftClientRequestProto> ordered(StreamObserver<RaftClientReplyProto> responseObserver) {
    final OrderedRequestStreamObserver so = new OrderedRequestStreamObserver(responseObserver);
    orderedStreamObservers.putNew(so);
    return so;
  }

  void closeAllOrderedRequestStreamObservers(RaftGroupId groupId) {
    LOG.debug("{}: closeAllOrderedRequestStreamObservers", getId());
    orderedStreamObservers.closeAllExisting(groupId);
  }

  @Override
  public StreamObserver<RaftClientRequestProto> unordered(StreamObserver<RaftClientReplyProto> responseObserver) {
    return new UnorderedRequestStreamObserver(responseObserver);
  }

  private final AtomicInteger streamCount = new AtomicInteger();

  private abstract class RequestStreamObserver implements StreamObserver<RaftClientRequestProto> {
    private final int id = streamCount.getAndIncrement();
    private final String name = getId() + "-" + JavaUtils.getClassSimpleName(getClass()) + id;
    private final StreamObserver<RaftClientReplyProto> responseObserver;
    private final AtomicBoolean isClosed = new AtomicBoolean();

    RequestStreamObserver(StreamObserver<RaftClientReplyProto> responseObserver) {
      LOG.debug("new {}", name);
      this.responseObserver = responseObserver;
      if (responseObserver instanceof ServerCallStreamObserver) {
        ServerCallStreamObserver<?> server =
            (ServerCallStreamObserver<?>) responseObserver;

        server.setOnCancelHandler(() -> {
          close(new RuntimeException("client cancelled"));
        });
      }
    }

    int getId() {
      return id;
    }

    String getName() {
      return name;
    }

    synchronized void responseNext(RaftClientReplyProto reply) {
      responseObserver.onNext(reply);
    }


    boolean setClose() {
      return isClosed.compareAndSet(false, true);
    }

    boolean isClosed() {
      return isClosed.get();
    }

    CompletableFuture<Void> processClientRequest(ReferenceCountedObject<RaftClientRequest> requestRef,
        Consumer<RaftClientReply> replyHandler) {
      final String errMsg = LOG.isDebugEnabled() ? "processClientRequest for " + requestRef.get() : "";
      return protocol.submitClientRequestAsync(requestRef
      ).thenAcceptAsync(replyHandler, executor
      ).exceptionally(exception -> {
        // TODO: the exception may be from either raft or state machine.
        // Currently we skip all the following responses when getting an
        // exception from the state machine.
        responseError(exception, () -> errMsg);
        return null;
      });
    }

    abstract void processClientRequest(ReferenceCountedObject<RaftClientRequest> requestRef);

    @Override
    public void onNext(RaftClientRequestProto request) {
      ReferenceCountedObject<RaftClientRequest> requestRef = null;
      try {
        final RaftClientRequest r = ClientProtoUtils.toRaftClientRequest(request);
        requestRef = ReferenceCountedObject.wrap(r, () -> {}, released -> {
          if (released) {
            zeroCopyRequestMarshaller.release(request);
          }
        });

        processClientRequest(requestRef);
      } catch (Exception e) {
        if (requestRef == null) {
          zeroCopyRequestMarshaller.release(request);
        }
        responseError(e, () -> "onNext for " + ClientProtoUtils.toString(request) + " in " + name);
      }
    }

    @Override
    public void onError(Throwable t) {
      // for now we just log a msg
      GrpcUtil.warn(LOG, () -> name + ": onError", t);
      close(t);
    }


    void responseError(Throwable t, Supplier<String> message) {
      if (!isClosed()) {
        t = JavaUtils.unwrapCompletionException(t);
        if (LOG.isDebugEnabled()) {
          LOG.debug(name + ": Failed " + message.get(), t);
        }
        close(GrpcUtil.wrapException(t));
      }
    }


    protected synchronized final void close(Throwable error) {
      if (!setClose()) {
        return;
      }

      try {
        if (error != null) {
          responseObserver.onError(error);
        } else {
          responseObserver.onCompleted();
        }
      } catch (Throwable ignored) {
        // response stream may possibly be already closed/failed so that the exception can be safely ignored.

      } finally {
        try {
          cleanup();
        } catch (Throwable ignored) {
        }
      }
    }

    protected abstract void cleanup();
  }

  private class UnorderedRequestStreamObserver extends RequestStreamObserver {
    /** Map: callId -> futures (seqNum is not set for unordered requests) */
    private final ConcurrentHashMap<Long, CompletableFuture<Void>> futures = new ConcurrentHashMap<>();

    private final AtomicInteger inflight = new AtomicInteger();
    private final AtomicBoolean clientCompleted = new AtomicBoolean();

    UnorderedRequestStreamObserver(StreamObserver<RaftClientReplyProto> responseObserver) {
      super(responseObserver);
    }

    @Override
    void processClientRequest(ReferenceCountedObject<RaftClientRequest> requestRef) {
      final long callId = requestRef.retain().getCallId();
      final CompletableFuture<Void> f;
      try {
        inflight.incrementAndGet();
        f = processClientRequest(requestRef, reply -> {
          if (!reply.isSuccess() && VexraLogTracer.client_req.isTrace()) {
            LOG.info("Failed request cid={}, reply={}", callId, reply);
          }
          final RaftClientReplyProto proto = ClientProtoUtils.toRaftClientReplyProto(reply);
          responseNext(proto);
        });
        f.whenComplete((r, e) -> {
          inflight.decrementAndGet();
          remove(callId);
          tryClose();
        });
      } finally {
        requestRef.release();
      }

      put(callId, f);
    }

    private void put(long callId, CompletableFuture<Void> f) {
      futures.put(callId, f);
    }
    private void remove(long callId) {
      futures.remove(callId);
    }


    @Override
    public void onCompleted() {
      clientCompleted.set(true);
      tryClose();
    }

    private void tryClose() {
      if (clientCompleted.get() && inflight.get() == 0) {
        close(null);
      }
    }

    @Override
    protected void cleanup() {
      for (CompletableFuture<?> f : futures.values()) {
        f.cancel(true);
      }
      futures.clear();
    }


  }

  private class OrderedRequestStreamObserver extends RequestStreamObserver {
    private final SlidingWindow.Server<PendingOrderedRequest, RaftClientReply> slidingWindow
        = new SlidingWindow.Server<>(getName(), COMPLETED);
    /** The {@link RaftGroupId} for this observer. */
    private final AtomicReference<RaftGroupId> groupId = new AtomicReference<>();

    OrderedRequestStreamObserver(StreamObserver<RaftClientReplyProto> responseObserver) {
      super(responseObserver);
    }

    RaftGroupId getGroupId() {
      return groupId.get();
    }

    void processClientRequest(PendingOrderedRequest pending) {
      final long seq = pending.getSeqNum();
      processClientRequest(pending.getRequestRef(),
          reply -> slidingWindow.receiveReply(seq, reply, this::sendReply))
          .whenComplete((r, e) -> {
            if (e != null) {
              pending.release();
            }
          });
    }

    @Override
    void processClientRequest(ReferenceCountedObject<RaftClientRequest> requestRef) {
      final RaftClientRequest request = requestRef.retain();
      try {
        if (isClosed()) {
          final AlreadyClosedException exception = new AlreadyClosedException(getName() + ": the stream is closed");
          responseError(exception, () -> "processClientRequest (stream already closed) for " + request);
        }

        final RaftGroupId requestGroupId = request.getRaftGroupId();
        // use the group id in the first request as the group id of this observer
        final RaftGroupId updated = groupId.updateAndGet(g -> g != null ? g : requestGroupId);

        if (!requestGroupId.equals(updated)) {
          final GroupMismatchException exception = new GroupMismatchException(getId()
              + ": The group (" + requestGroupId + ") of " + request.getClientId()
              + " does not match the group (" + updated + ") of the " + JavaUtils.getClassSimpleName(getClass()));
          responseError(exception, () -> "processClientRequest (Group mismatched) for " + request);
          return;
        }
        final PendingOrderedRequest pending = new PendingOrderedRequest(requestRef);
        try {
          slidingWindow.receivedRequest(pending, this::processClientRequest);
        } catch (Exception e) {
          pending.release();
          throw e;
        }
      } finally {
        requestRef.release();
      }
    }

    private void sendReply(PendingOrderedRequest ready) {
      if (isClosed()) {
        return;
      }

      Preconditions.assertTrue(ready.hasReply());
      if (ready == COMPLETED) {
        close(null);
      } else {
        LOG.debug("{}: sendReply seq={}, {}", getName(), ready.getSeqNum(), ready.getReply());
        responseNext(ClientProtoUtils.toRaftClientReplyProto(ready.getReply()));
      }
    }

    @Override
    public void onCompleted() {
      if (isClosed()) {
        return;
      }

      try {
        boolean ready =
            slidingWindow.endOfRequests(this::sendReply);

        if (ready) {
          close(null);
        }

      } catch (Throwable t) {
        close(t);
      }
    }

    @Override
    protected void cleanup() {
      slidingWindow.close();
      orderedStreamObservers.removeExisting(this);
    }

  }
}
