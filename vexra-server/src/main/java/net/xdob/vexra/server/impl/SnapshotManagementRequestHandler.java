package net.xdob.vexra.server.impl;

import net.xdob.vexra.protocol.RaftClientReply;
import net.xdob.vexra.protocol.SnapshotManagementRequest;
import net.xdob.vexra.protocol.exceptions.TimeoutIOException;
import net.xdob.vexra.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

class SnapshotManagementRequestHandler {
  static final Logger LOG = LoggerFactory.getLogger(SnapshotManagementRequestHandler.class);


  class PendingRequest {
    private final SnapshotManagementRequest request;
    private final CompletableFuture<RaftClientReply> replyFuture = new CompletableFuture<>();
    private final AtomicBoolean triggerTakingSnapshot = new AtomicBoolean(true);

    PendingRequest(SnapshotManagementRequest request) {
      LOG.info("new PendingRequest " + request);
      this.request = request;
    }

    CompletableFuture<RaftClientReply> getReplyFuture() {
      return replyFuture;
    }

    boolean shouldTriggerTakingSnapshot() {
      return triggerTakingSnapshot.getAndSet(false);
    }

    void complete(long index) {
      LOG.info("{}: Successfully take snapshot at index {} for request {}", server.getMemberId(), index, request);
      replyFuture.complete(server.newSuccessReply(request, index));
    }

    void timeout() {
      replyFuture.completeExceptionally(new TimeoutIOException(
          server.getMemberId() + ": Failed to take a snapshot within timeout " + request.getTimeoutMs() + "ms"));
    }


    @Override
    public String toString() {
      return request.toString();
    }
  }

  static class PendingRequestReference {
    private final AtomicReference<PendingRequest> ref = new AtomicReference<>();

    Optional<PendingRequest> get() {
      return Optional.ofNullable(ref.get());
    }

    Optional<PendingRequest> getAndSetNull() {
      return Optional.ofNullable(ref.getAndSet(null));
    }

    PendingRequest getAndUpdate(Supplier<PendingRequest> supplier) {
      return ref.getAndUpdate(p -> p != null? p: supplier.get());
    }
  }

  private final RaftServerImpl server;
  private final TimeoutExecutor scheduler = TimeoutExecutor.getInstance();
  private final PendingRequestReference pending = new PendingRequestReference();


  SnapshotManagementRequestHandler(RaftServerImpl server) {
    this.server = server;
  }

  CompletableFuture<RaftClientReply> takingSnapshotAsync(SnapshotManagementRequest request) {
    final MemoizedSupplier<PendingRequest> supplier = JavaUtils.memoize(() -> new PendingRequest(request));
    final PendingRequest previous = pending.getAndUpdate(supplier);
    if (previous != null) {
      return previous.getReplyFuture();
    }

    server.getState().notifyStateMachineUpdater();
    scheduler.onTimeout(TimeDuration.valueOf(request.getTimeoutMs(), TimeUnit.MILLISECONDS),
        this::timeout, LOG, () -> "Timeout check failed for snapshot request: " + request);
    return supplier.get().getReplyFuture();
  }

  boolean shouldTriggerTakingSnapshot() {
    return pending.get().map(PendingRequest::shouldTriggerTakingSnapshot).orElse(false);
  }

  void completeTakingSnapshot(long index) {
    pending.getAndSetNull().ifPresent(p -> p.complete(index));
  }

  void timeout() {
    pending.getAndSetNull().ifPresent(PendingRequest::timeout);
  }
}
