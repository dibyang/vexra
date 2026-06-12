package net.xdob.vexra.adb.db;

import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * ADB region commit RPC client。
 *
 * <p>该 client 将 {@link AdbRegionCommitClient} 的 prewrite、commit、rollback
 * 语义映射到可替换的 {@link AdbRegionCommitTransport}。它负责统一处理 transport
 * 空响应、失败响应、异常和超时，便于后续把 transport 替换为真实 Raft/RPC 实现。</p>
 */
public final class AdbRpcRegionCommitClient
    implements AdbRegionCommitClient, AutoCloseable {
  private final AdbRegionCommitTransport transport;
  private final long timeoutMillis;
  private final ScheduledExecutorService scheduler;

  /**
   * 创建 region commit RPC client。
   *
   * @param transport region commit transport
   * @param timeoutMillis 单阶段超时时间，0 表示不启用 client 侧超时
   */
  public AdbRpcRegionCommitClient(AdbRegionCommitTransport transport,
      long timeoutMillis) {
    if (timeoutMillis < 0) {
      throw new IllegalArgumentException("timeoutMillis is negative: "
          + timeoutMillis);
    }
    this.transport = Objects.requireNonNull(transport, "transport == null");
    this.timeoutMillis = timeoutMillis;
    this.scheduler = Executors.newSingleThreadScheduledExecutor(
        new DaemonThreadFactory());
  }

  @Override
  public CompletableFuture<Void> prewriteAsync(
      AdbRegionCommitRequest request) {
    return send(AdbRegionCommitPhase.PREWRITE, request);
  }

  @Override
  public CompletableFuture<Void> commitAsync(AdbRegionCommitRequest request) {
    return send(AdbRegionCommitPhase.COMMIT, request);
  }

  @Override
  public CompletableFuture<Void> rollbackAsync(
      AdbRegionCommitRequest request) {
    return send(AdbRegionCommitPhase.ROLLBACK, request);
  }

  /**
   * 关闭 client 侧超时调度器。
   */
  @Override
  public void close() {
    scheduler.shutdownNow();
  }

  private CompletableFuture<Void> send(AdbRegionCommitPhase phase,
      AdbRegionCommitRequest request) {
    Objects.requireNonNull(request, "request == null");
    CompletableFuture<AdbRegionCommitResponse> response;
    try {
      response = transport.sendAsync(phase, request);
      if (response == null) {
        return failed(new NullPointerException("sendAsync returned null"));
      }
    } catch (RuntimeException e) {
      return failed(e);
    }
    return withTimeout(response, phase, request).thenCompose(
        result -> mapResponse(phase, request, result));
  }

  private CompletableFuture<AdbRegionCommitResponse> withTimeout(
      CompletableFuture<AdbRegionCommitResponse> source,
      AdbRegionCommitPhase phase, AdbRegionCommitRequest request) {
    if (timeoutMillis == 0) {
      return source;
    }
    CompletableFuture<AdbRegionCommitResponse> result =
        new CompletableFuture<>();
    ScheduledFuture<?> timeout = scheduler.schedule(() -> {
      boolean completed = result.completeExceptionally(new SQLException(
          "Timed out executing ADB region " + phase
              + ", regionId=" + request.getRegionId()
              + ", timeoutMillis=" + timeoutMillis));
      if (completed) {
        source.cancel(true);
      }
    }, timeoutMillis, TimeUnit.MILLISECONDS);
    source.whenComplete((value, error) -> {
      timeout.cancel(false);
      if (error != null) {
        result.completeExceptionally(unwrap(error));
      } else {
        result.complete(value);
      }
    });
    return result;
  }

  private CompletableFuture<Void> mapResponse(AdbRegionCommitPhase phase,
      AdbRegionCommitRequest request, AdbRegionCommitResponse response) {
    if (response == null) {
      return failed(new NullPointerException("region commit response is null"));
    }
    if (response.isSuccess()) {
      return CompletableFuture.completedFuture(null);
    }
    String message = response.getMessage().isEmpty() ? "unknown failure"
        : response.getMessage();
    return failed(new SQLException("ADB region " + phase + " failed"
        + ", regionId=" + request.getRegionId()
        + ", leaderId=" + request.getLeaderId()
        + ": " + message, response.getCause()));
  }

  private static CompletableFuture<Void> failed(Throwable error) {
    CompletableFuture<Void> future = new CompletableFuture<>();
    future.completeExceptionally(error);
    return future;
  }

  private static Throwable unwrap(Throwable error) {
    Throwable current = error;
    while (current instanceof CompletionException && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private static final class DaemonThreadFactory implements ThreadFactory {
    @Override
    public Thread newThread(Runnable runnable) {
      Thread thread = new Thread(runnable, "adb-region-commit-timeout");
      thread.setDaemon(true);
      return thread;
    }
  }
}
