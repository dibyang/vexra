package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.DataKey;
import net.xdob.vexra.cluster.region.RegionMetadata;
import net.xdob.vexra.cluster.region.RegionRouter;
import net.xdob.vexra.cluster.txn.TwoPhaseCommitContext;
import net.xdob.vexra.cluster.txn.TxnParticipant;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * ADB region commit 协调器。
 *
 * <p>该协调器位于 {@link TxnManager} durable commit 前，负责把事务 write set
 * 路由到 region，校验 leader/epoch，并把 region 事务阶段请求交给
 * {@link AdbRegionCommitClient}。单 region 事务保持旧 commit fast path；跨 region
 * 事务在这里执行最小 2PC 编排。</p>
 */
public final class AdbRegionCommitCoordinator {
  private static final long DEFAULT_LOCK_TTL_MILLIS = 3000;

  private final RegionRouter router;
  private final AdbRegionCommitClient client;

  /**
   * 创建 ADB region commit 协调器。
   *
   * @param router region 路由快照
   * @param client region commit client
   */
  public AdbRegionCommitCoordinator(RegionRouter router,
      AdbRegionCommitClient client) {
    this.router = Objects.requireNonNull(router, "router == null");
    this.client = Objects.requireNonNull(client, "client == null");
  }

  /**
   * 提交当前事务 write set 对应的 region 写入。
   *
   * @param txn 当前事务
   * @param commitTs commit timestamp
   * @param writeKeys write set key 快照
   * @param metas 提交元数据
   * @return 提交完成 future
   */
  public CompletableFuture<Void> commitAsync(Transaction2 txn, long commitTs,
      Collection<DataKey> writeKeys, List<Meta> metas) {
    try {
      List<RegionWriteSet> participants = buildParticipants(txn, commitTs,
          writeKeys, metas);
      if (participants.size() == 1) {
        return nonNullFuture(client.commitAsync(participants.get(0).request),
            "commitAsync returned null");
      }
      executeTwoPhaseCommit(txn, commitTs, participants);
      return CompletableFuture.completedFuture(null);
    } catch (Throwable e) {
      return failed(unwrap(e));
    }
  }

  private List<RegionWriteSet> buildParticipants(Transaction2 txn, long commitTs,
      Collection<DataKey> writeKeys, List<Meta> metas) {
    Objects.requireNonNull(txn, "txn == null");
    Objects.requireNonNull(writeKeys, "writeKeys == null");
    if (writeKeys.isEmpty()) {
      throw new IllegalArgumentException("writeKeys is empty");
    }

    Map<String, RegionBuilder> builders = new LinkedHashMap<>();
    DataKey primaryKey = null;
    String primaryRegionId = null;
    for (DataKey key : writeKeys) {
      RegionMetadata region = router.route(key.toBytes());
      validateRegion(region);
      if (primaryKey == null) {
        primaryKey = key;
        primaryRegionId = region.getRegionId();
      }
      RegionBuilder builder = builders.get(region.getRegionId());
      if (builder == null) {
        builder = new RegionBuilder(region);
        builders.put(region.getRegionId(), builder);
      }
      builder.writeKeys.add(key);
      RowValue value = txn.getLocalWrite(key);
      if (value == null) {
        throw new IllegalStateException("Missing local write value, key="
            + key);
      }
      builder.mutations.add(new AdbRegionMutation(key, value));
    }

    List<RegionWriteSet> participants = new ArrayList<>();
    for (RegionBuilder builder : builders.values()) {
      RegionMetadata region = builder.region;
      boolean primaryRegion = region.getRegionId().equals(primaryRegionId);
      AdbRegionCommitRequest request = new AdbRegionCommitRequest(
          region.getRegionId(), region.getEpoch(),
          region.getReplicaMetadata().getLeaderId(), txn.getTxnId(),
          txn.getStartTs(), commitTs, primaryRegionId, primaryKey,
          DEFAULT_LOCK_TTL_MILLIS, primaryRegion, builder.writeKeys,
          builder.mutations, metas);
      participants.add(new RegionWriteSet(request, primaryRegion));
    }
    return participants;
  }

  private void validateRegion(RegionMetadata region) {
    Objects.requireNonNull(region, "region == null");
    String leaderId = region.getReplicaMetadata().getLeaderId();
    if (leaderId == null || leaderId.trim().isEmpty()) {
      throw new IllegalStateException("Region leader is empty, regionId="
          + region.getRegionId());
    }
    if (region.getEpoch() != region.getReplicaMetadata().getEpoch()) {
      throw new IllegalStateException("Region epoch mismatch, regionId="
          + region.getRegionId() + ", regionEpoch=" + region.getEpoch()
          + ", replicaEpoch=" + region.getReplicaMetadata().getEpoch());
    }
  }

  private void executeTwoPhaseCommit(Transaction2 txn, long commitTs,
      List<RegionWriteSet> participants) {
    if (commitTs <= txn.getStartTs()) {
      throw new IllegalArgumentException("commitTs must be greater than startTs");
    }
    TwoPhaseCommitContext context = TwoPhaseCommitContext.create(
        txn.getStartTs(), toTxnParticipants(participants));
    List<RegionWriteSet> prewritten = new ArrayList<>();
    boolean primaryCommitted = false;
    try {
      for (RegionWriteSet participant : participants) {
        joinRegionFuture(client.prewriteAsync(participant.request),
            "prewriteAsync returned null");
        prewritten.add(participant);
      }
      context = context.prewrite();

      RegionWriteSet primary = primaryParticipant(participants);
      joinRegionFuture(client.commitAsync(primary.request),
          "commitAsync returned null");
      primaryCommitted = true;
      for (RegionWriteSet participant : participants) {
        if (!participant.primary) {
          joinRegionFuture(client.commitAsync(participant.request),
              "commitAsync returned null");
        }
      }
      context.commit(commitTs);
    } catch (Throwable e) {
      Throwable cause = unwrap(e);
      if (!primaryCommitted) {
        rollbackPrewritten(prewritten, cause);
      }
      throw new RegionCommitException(cause);
    }
  }

  private List<TxnParticipant> toTxnParticipants(
      List<RegionWriteSet> participants) {
    List<TxnParticipant> txnParticipants = new ArrayList<>();
    for (RegionWriteSet participant : participants) {
      txnParticipants.add(new TxnParticipant(
          participant.request.getRegionId(), participant.primary));
    }
    return txnParticipants;
  }

  private RegionWriteSet primaryParticipant(List<RegionWriteSet> participants) {
    for (RegionWriteSet participant : participants) {
      if (participant.primary) {
        return participant;
      }
    }
    throw new IllegalStateException("primary participant is missing");
  }

  private void rollbackPrewritten(List<RegionWriteSet> prewritten,
      Throwable primaryFailure) {
    for (RegionWriteSet participant : prewritten) {
      try {
        joinRegionFuture(client.rollbackAsync(participant.request),
            "rollbackAsync returned null");
      } catch (Throwable rollbackError) {
        primaryFailure.addSuppressed(unwrap(rollbackError));
      }
    }
  }

  private static void joinRegionFuture(CompletableFuture<Void> future,
      String nullMessage) {
    nonNullFuture(future, nullMessage).join();
  }

  private static CompletableFuture<Void> nonNullFuture(
      CompletableFuture<Void> future, String nullMessage) {
    if (future == null) {
      throw new NullPointerException(nullMessage);
    }
    return future;
  }

  private static Throwable unwrap(Throwable t) {
    while ((t instanceof CompletionException || t instanceof RegionCommitException)
        && t.getCause() != null) {
      t = t.getCause();
    }
    return t;
  }

  private static CompletableFuture<Void> failed(Throwable error) {
    CompletableFuture<Void> future = new CompletableFuture<>();
    future.completeExceptionally(error);
    return future;
  }

  private static final class RegionBuilder {
    private final RegionMetadata region;
    private final List<DataKey> writeKeys = new ArrayList<>();
    private final List<AdbRegionMutation> mutations = new ArrayList<>();

    private RegionBuilder(RegionMetadata region) {
      this.region = region;
    }
  }

  private static final class RegionWriteSet {
    private final AdbRegionCommitRequest request;
    private final boolean primary;

    private RegionWriteSet(AdbRegionCommitRequest request, boolean primary) {
      this.request = request;
      this.primary = primary;
    }
  }

  private static final class RegionCommitException extends RuntimeException {
    private RegionCommitException(Throwable cause) {
      super(cause);
    }
  }
}
