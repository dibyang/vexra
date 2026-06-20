package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.DataKey;
import net.xdob.vexra.cluster.region.RegionMetadata;
import net.xdob.vexra.cluster.region.RegionRouter;
import net.xdob.vexra.cluster.txn.TwoPhaseCommitContext;
import net.xdob.vexra.cluster.txn.TxnParticipant;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

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
  private final Function<DataKey, DataKey> keyMapper;
  private final boolean prewriteSingleRegion;
  private final AdbDurableCommitRecorder commitRecorder;
  private final AdbRegionWriteGuard writeGuard;
  private final AdbCrossRegionTxnGuard txnRegionGuard;
  private final long routeEpoch;

  /**
   * 创建 ADB region commit 协调器。
   *
   * @param router region 路由快照
   * @param client region commit client
   */
  public AdbRegionCommitCoordinator(RegionRouter router,
      AdbRegionCommitClient client) {
    this(router, client, Function.identity());
  }

  /**
   * 创建 ADB region commit 协调器，并在路由/远端提交前映射 write key。
   *
   * <p>该构造用于 SQL server 与 region node 暂未共享真实 catalog 的过渡阶段：
   * SQL 表本地 table id 可以显式映射成远端 region table id。默认构造保持 identity
   * 映射，不影响单机和既有分布式写入路径。</p>
   *
   * @param router region 路由快照
   * @param client region commit client
   * @param keyMapper 写入 key 映射器
   */
  public AdbRegionCommitCoordinator(RegionRouter router,
      AdbRegionCommitClient client, Function<DataKey, DataKey> keyMapper) {
    this(router, client, keyMapper, false);
  }

  /**
   * 创建 ADB region commit 协调器，并可强制单 region 也执行 PREWRITE。
   *
   * <p>本地 bridge client 可以复用既有单 region fast path；真实远端 Raft/RPC
   * client 需要在 COMMIT 前写入 durable intent，因此可通过该开关让单 region 也走
   * prewrite + commit 流程。</p>
   *
   * @param router region 路由快照
   * @param client region commit client
   * @param keyMapper 写入 key 映射器
   * @param prewriteSingleRegion 是否强制单 region 也执行 PREWRITE
   */
  public AdbRegionCommitCoordinator(RegionRouter router,
      AdbRegionCommitClient client, Function<DataKey, DataKey> keyMapper,
      boolean prewriteSingleRegion) {
    this(router, client, keyMapper, prewriteSingleRegion,
        AdbDurableCommitRecorder.noop());
  }

  /**
   * 创建带 durable commit 记录器的 region commit 协调器。
   *
   * @param router region 路由快照
   * @param client region commit client
   * @param keyMapper 写入 key 映射器
   * @param prewriteSingleRegion 是否强制单 region 也执行 PREWRITE
   * @param commitRecorder durable commit 状态记录器
   */
  public AdbRegionCommitCoordinator(RegionRouter router,
      AdbRegionCommitClient client, Function<DataKey, DataKey> keyMapper,
      boolean prewriteSingleRegion,
      AdbDurableCommitRecorder commitRecorder) {
    this(router, client, keyMapper, prewriteSingleRegion, commitRecorder,
        AdbRegionWriteGuard.NOOP);
  }

  /**
   * 创建带 durable commit 记录器和写入保护的 region commit 协调器。
   *
   * @param router region 路由快照
   * @param client region commit client
   * @param keyMapper 写入 key 映射器
   * @param prewriteSingleRegion 是否强制单 region 也执行 PREWRITE
   * @param commitRecorder durable commit 状态记录器
   * @param writeGuard commit 前写入保护钩子
   */
  public AdbRegionCommitCoordinator(RegionRouter router,
      AdbRegionCommitClient client, Function<DataKey, DataKey> keyMapper,
      boolean prewriteSingleRegion,
      AdbDurableCommitRecorder commitRecorder,
      AdbRegionWriteGuard writeGuard) {
    this(router, client, keyMapper, prewriteSingleRegion, commitRecorder,
        writeGuard, AdbCrossRegionTxnGuard.noop(), -1);
  }

  /**
   * 创建带 durable commit、写入保护和事务 region guard 的协调器。
   *
   * @param router region 路由快照
   * @param client region commit client
   * @param keyMapper 写入 key 映射器
   * @param prewriteSingleRegion 是否强制单 region 也执行 PREWRITE
   * @param commitRecorder durable commit 状态记录器
   * @param writeGuard commit 前写入保护钩子
   * @param txnRegionGuard 事务 region 生产边界 guard
   * @param routeEpoch 当前路由快照 epoch，未知时传 -1
   */
  public AdbRegionCommitCoordinator(RegionRouter router,
      AdbRegionCommitClient client, Function<DataKey, DataKey> keyMapper,
      boolean prewriteSingleRegion,
      AdbDurableCommitRecorder commitRecorder,
      AdbRegionWriteGuard writeGuard,
      AdbCrossRegionTxnGuard txnRegionGuard, long routeEpoch) {
    this.router = Objects.requireNonNull(router, "router == null");
    this.client = Objects.requireNonNull(client, "client == null");
    this.keyMapper = Objects.requireNonNull(keyMapper, "keyMapper == null");
    this.prewriteSingleRegion = prewriteSingleRegion;
    this.commitRecorder = Objects.requireNonNull(commitRecorder,
        "commitRecorder == null");
    this.writeGuard = Objects.requireNonNull(writeGuard,
        "writeGuard == null");
    this.txnRegionGuard = Objects.requireNonNull(txnRegionGuard,
        "txnRegionGuard == null");
    this.routeEpoch = routeEpoch;
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
      writeGuard.beforeCommit();
      List<RegionWriteSet> participants = buildParticipants(txn, commitTs,
          writeKeys, metas);
      txnRegionGuard.beforeCommit("region-commit", routeEpoch,
          regionIds(participants));
      if (participants.size() == 1 && !prewriteSingleRegion) {
        return commitSingleRegion(participants.get(0));
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
      DataKey mappedKey = Objects.requireNonNull(keyMapper.apply(key),
          "mapped write key is null");
      RegionMetadata region = router.route(mappedKey.toBytes());
      validateRegion(region);
      if (primaryKey == null) {
        primaryKey = mappedKey;
        primaryRegionId = region.getRegionId();
      }
      RegionBuilder builder = builders.get(region.getRegionId());
      if (builder == null) {
        builder = new RegionBuilder(region);
        builders.put(region.getRegionId(), builder);
      }
      builder.writeKeys.add(mappedKey);
      RowValue value = txn.getLocalWrite(key);
      if (value == null) {
        throw new IllegalStateException("Missing local write value, key="
            + key);
      }
      builder.mutations.add(new AdbRegionMutation(mappedKey, value));
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
        participant.marker = commitRecorder.prewritten(participant.request);
        prewritten.add(participant);
      }
      context = context.prewrite();

      RegionWriteSet primary = primaryParticipant(participants);
      joinRegionFuture(client.commitAsync(primary.request),
          "commitAsync returned null");
      markCommitted(primary);
      primaryCommitted = true;
      for (RegionWriteSet participant : participants) {
        if (!participant.primary) {
          joinRegionFuture(client.commitAsync(participant.request),
              "commitAsync returned null");
          markCommitted(participant);
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

  private CompletableFuture<Void> commitSingleRegion(RegionWriteSet writeSet)
      throws SQLException {
    writeSet.marker = commitRecorder.prewritten(writeSet.request);
    return nonNullFuture(client.commitAsync(writeSet.request),
        "commitAsync returned null").thenApply(ignored -> {
      try {
        markCommitted(writeSet);
        return null;
      } catch (SQLException e) {
        throw new CompletionException(e);
      }
    });
  }

  private void markCommitted(RegionWriteSet writeSet) throws SQLException {
    writeSet.marker = commitRecorder.raftCommitted(writeSet.marker);
    writeSet.marker = commitRecorder.storeCommitted(writeSet.marker);
    writeSet.marker = commitRecorder.replied(writeSet.marker);
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

  private static List<String> regionIds(List<RegionWriteSet> participants) {
    List<String> regionIds = new ArrayList<>();
    for (RegionWriteSet participant : participants) {
      regionIds.add(participant.request.getRegionId());
    }
    return regionIds;
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
        participant.marker = commitRecorder.rolledBack(participant.marker,
            primaryFailure);
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
    private AdbDurableCommitMarker marker;

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
