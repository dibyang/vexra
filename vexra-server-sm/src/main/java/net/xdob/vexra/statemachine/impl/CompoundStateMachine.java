package net.xdob.vexra.statemachine.impl;

import com.google.common.base.Stopwatch;
import com.google.common.collect.Maps;
import com.google.protobuf.InvalidProtocolBufferException;
import net.xdob.vexra.io.Digest;
import net.xdob.vexra.proto.raft.LogEntryProto;
import net.xdob.vexra.proto.sm.WrapReplyProto;
import net.xdob.vexra.proto.sm.WrapRequestProto;
import net.xdob.vexra.protocol.*;
import net.xdob.vexra.rpc.CallId;
import net.xdob.vexra.security.crypto.factory.PasswordEncoderFactories;
import net.xdob.vexra.security.crypto.password.PasswordEncoder;
import net.xdob.vexra.server.RaftServer;
import net.xdob.vexra.server.VexraLogTracer;
import net.xdob.vexra.server.protocol.TermIndex;
import net.xdob.vexra.server.raftlog.RaftLog;
import net.xdob.vexra.server.raftlog.TxIndex;
import net.xdob.vexra.server.storage.FileInfo;
import net.xdob.vexra.server.storage.RaftStorage;
import net.xdob.vexra.statemachine.ServerStateSupport;
import net.xdob.vexra.statemachine.SnapshotInfo;
import net.xdob.vexra.statemachine.StateMachineStorage;
import net.xdob.vexra.statemachine.TransactionContext;
import net.xdob.vexra.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLNonTransientException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class CompoundStateMachine extends BaseStateMachine implements SMPluginContext {
  static final Logger LOG = LoggerFactory.getLogger(CompoundStateMachine.class);



  private final List<LeaderChangedListener> leaderChangedListeners = new ArrayList<>();

  private final PasswordEncoder passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
  private ScheduledExecutorService scheduler;
  private final FileListStateMachineStorage storage = new FileListStateMachineStorage();
  private MemoizedSupplier<ServerStateSupport> serverStateSupport;
  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();


	private final AtomicReference<RaftPeerId> leaderId = new AtomicReference<>(RaftPeerId.EMPTY);
	private RaftGroup raftGroup;
	private final TxIndex lastEndTxIndex;
	private final AtomicLong firstTxIndex = new AtomicLong(RaftLog.INVALID_LOG_INDEX);
	private final Set<String> txSet = new ConcurrentSkipListSet<>();
	/**
	 * 最后一个有效事务阶段性索引(可以做快照的)
	 * TermIndex.INITIAL_VALUE表示没有有效事务阶段性索引，当前不可以做快照
	 */
	private final AtomicReference<TermIndex> lastValidTxTermIndex =new AtomicReference<>(TermIndex.INITIAL_VALUE);

	private volatile CompletableFuture<RaftPeerId> leaderChangedFuture = new CompletableFuture<>();

  private Map<String,SMPlugin> pluginMap = Maps.newConcurrentMap();
	volatile boolean isLeader = false;
	private ExecutorService executor;

	public CompoundStateMachine(RaftGroupId groupId, RaftPeerId peerId) {
    super(groupId, peerId);
    lastEndTxIndex = new TxIndex("LastEndTxIndex", RaftLog.INVALID_LOG_INDEX);
	}


	public boolean updateLastEndTxIndexToMax(long newIndex) {
		return lastEndTxIndex.updateToMax(newIndex,
				message -> LOG.debug("updateLastEndTxIndexToMax {}", message));
	}


	public long getLastEndTxIndex() {
		return lastEndTxIndex.get();
	}

	public long getFirstTxIndex() {
		return firstTxIndex.get();
	}

	public void beginTx(String sessionId){
		txSet.add(sessionId);
		updateLastValidTxTermIndex();
	}

	private void updateLastValidTxTermIndex() {
		synchronized (lastValidTxTermIndex) {
			//改为没有事务才允许快照
			if (!txSet.isEmpty()) {
				lastValidTxTermIndex.set(TermIndex.INITIAL_VALUE);
			} else {
				long lastEndTxIndex = getLastEndTxIndex();
				TermIndex lastEndTxTermIndex = serverStateSupport.get().getTermIndex(lastEndTxIndex);
				lastValidTxTermIndex.set(lastEndTxTermIndex);
			}
			if(VexraLogTracer.tx.isTrace()) {
				LOG.info("updateLastValidTxTermIndex lastEndTxIndex:{}, txSet={}, lastValidTxTermIndex={}", lastEndTxIndex, txSet, lastValidTxTermIndex.get());
			}
		}
	}

	public void endTx(String sessionId, long endIndex){
		if(sessionId!=null){
			txSet.remove(sessionId);
		}
		if(endIndex>0){
      updateLastEndTxIndexToMax(endIndex);
    }
		updateLastValidTxTermIndex();
	}

  public void cleanTx(){
    txSet.clear();
    updateLastValidTxTermIndex();
  }


	/**
	 * 获取最新事务阶段性索引
	 * 最后一个有效事务阶段性索引(可以做快照的)
	 * TermIndex.INITIAL_VALUE表示没有有效事务阶段性索引，当前不可以做快照
	 * @return 获取最新事务阶段性索引
	 */
	@Override
	public TermIndex getLastValidTxTermIndex() {
		return lastValidTxTermIndex.get();
	}

	private AutoCloseableLock readLock() {
    return AutoCloseableLock.acquire(lock.readLock());
  }

  private AutoCloseableLock writeLock() {
    return AutoCloseableLock.acquire(lock.writeLock());
  }


	@Override
	public AutoCloseableLock writeLock(String sessionId) {
		return AutoCloseableLock.acquire(lock.writeLock());
	}

	@Override
	public AutoCloseableLock readLock(String sessionId) {
		return AutoCloseableLock.acquire(lock.readLock());
	}

	@Override
	public void cleanupSessionLock(String sessionId) {
		//lock.cleanupSessionLock(sessionId);
	}


  /**
   * Leader自主写入日志的核心工具方法
   * @return CompletableFuture<Message> 写入结果，可异步等待完成
   */
  public CompletableFuture<RaftClientReply> writeLogByLeader(Message msg)  {
    // 1. 前置校验：必须是Leader才能写入（Raft协议强制，非Leader写入会直接失败）
    if (!isLeader( true)) {
      IllegalStateException e = new IllegalStateException("Not Leader or node not ready, no write permission for logs.");
      LOG.warn("writeLogByLeader failed: {}", e.getMessage());
      return failedFuture(e);
    }

    // 3. 核心API：构造【Leader本地请求】，无需RaftClient，无网络请求
    // RaftClientRequest.newBuilder() 构建的是「本地请求」，source=自身，leaderId=自身


    // ============ 核心修复：构造一个固定的clientId（关键！） ============
    ClientId clientId = ClientId.valueOf("leader#"+peerId.getId()); // 虚拟客户端ID，任意字符串都可以

    RaftClientRequest request = RaftClientRequest.newBuilder()
        .setServerId(peerId) // 当前Leader的节点ID
        .setGroupId(getRaftGroup().getGroupId()) // Raft集群组ID
        .setLeaderId(peerId)// Leader就是自己
        .setMessage(msg) // 日志内容
        .setClientId(clientId)
        .setCallId(CallId.getAndIncrement())
        .setType(RaftClientRequest.writeRequestType())
        .build();

    // 4. 核心写入：调用RaftServer内核方法，提交日志，触发复制+状态机应用
    // 这个方法是Leader本地提交的核心入口，和客户端写入走同一个内核逻辑
    ServerStateSupport support = serverStateSupport.getOptional().orElse(null);
    if(support!=null){
      return support.writeAsync(request);
    }else {
      IllegalStateException e = new IllegalStateException("ServerStateSupport is not initialized");
      return failedFuture(e);
    }
  }

  private static CompletableFuture<RaftClientReply> failedFuture(Exception e) {
    CompletableFuture<RaftClientReply> future = new CompletableFuture<>();
    future.completeExceptionally(e);
    return future;
  }

  public void addSMPlugin(SMPlugin plugin){
    plugin.setSMPluginContext(this);

    pluginMap.put(plugin.getId(), plugin);
  }

  public <T extends SMPlugin> Optional<T> getSMPlugin(Class<T> clazz){
    return (Optional<T>) pluginMap.values().stream()
        .filter(e->e.getClass().equals(clazz))
        .findFirst();
  }

  @Override
  public StateMachineStorage getStateMachineStorage() {
    return storage;
  }

  @Override
  public void initialize(RaftServer server, RaftGroupId groupId, RaftPeerId peerId,
                         RaftStorage raftStorage, MemoizedSupplier<ServerStateSupport> serverSupportSupplier) throws IOException {
    super.initialize(server, groupId, peerId, raftStorage, serverSupportSupplier);

		// 创建RaftClient

		for (RaftGroup serverGroup : server.getGroups()) {
			if(serverGroup.getGroupId().equals(groupId)){
				this.raftGroup = serverGroup;
				break;
			}
		}

    this.serverStateSupport = serverSupportSupplier;
    if(this.scheduler==null){
      this.scheduler = Executors.newScheduledThreadPool(6);
    }

		if(executor==null) {
			executor = Concurrents3.newThreadPoolWithMax(true, 8,
					"sm-" + peerId);
		}

		storage.init(raftStorage);
    for (SMPlugin plugin : pluginMap.values()) {
      plugin.initialize(server, groupId, peerId, raftStorage);
    }
		LOG.info(marker, "{} initialize", getPeerId());
		restoreFromSnapshot(getLatestSnapshot());
  }

	 public RaftGroup getRaftGroup() {
		return raftGroup;
	}

	@Override
  public void reinitialize() throws IOException {
		LOG.info(marker, "{} reinitialize", getPeerId());
    restoreFromSnapshot(getLatestSnapshot());
    for (SMPlugin plugin : pluginMap.values()) {
      plugin.reinitialize();
    }
  }

  public void addLeaderChangedListener(LeaderChangedListener listener) {
    leaderChangedListeners.add(listener);
  }

  public void removeLeaderChangedListener(LeaderChangedListener listener) {
    leaderChangedListeners.remove(listener);
  }



  public CompletableFuture<RaftPeerId> getLeaderChangedFuture() {
    return leaderChangedFuture;
  }



  @Override
  public void changeToCandidate(RaftGroupMemberId groupMemberId) {
		leaderId.set(RaftPeerId.EMPTY);
    LOG.info(marker, "changeToCandidate: groupMemberId={}", groupMemberId);
    for (LeaderChangedListener listener : leaderChangedListeners) {
      try {
        listener.changeToCandidate(groupMemberId);
      }catch (Exception e){
        LOG.warn(marker, "{} listener.changeToCandidate error", listener, e);
      }
    }
  }

  @Override
  public void notifyLeaderChanged(RaftGroupMemberId groupMemberId, RaftPeerId newLeaderId) {
    LOG.info(marker, "leaderChanged: groupMemberId={}, newLeaderId={}", groupMemberId.getPeerId(), newLeaderId);
		leaderId.set(newLeaderId);
		isLeader = groupMemberId.getPeerId().isOwner(newLeaderId);
    scheduler.submit(()->fireLeaderStateEvent(isLeader));
    leaderChangedFuture.complete(newLeaderId);
  }

  private void fireLeaderStateEvent(boolean isLeader) {
    LOG.info(marker, "fireLeaderStateEvent: isLeader={}", isLeader);
    for (LeaderChangedListener listener : leaderChangedListeners) {
      try {
        listener.notifyLeaderChanged(isLeader);
      }catch (Exception e){
        LOG.warn(marker, "{} listener.notifyLeaderChanged error", listener, e);
      }
    }
  }

  @Override
  public TransactionContext startTransaction(RaftClientRequest request) throws IOException {
    Message message = request.getMessage();
    WrapRequestProto requestProto = WrapRequestProto.parseFrom(message.getContent());
    TransactionContext transactionContext = TransactionContext.newBuilder()
        .setStateMachine(this)
        .setClientRequest(request).build();
    String pluginId = requestProto.getType();
    SMPlugin smPlugin = pluginMap.get(pluginId);
    if(smPlugin!=null) {
      try {
        smPlugin.startTransaction(transactionContext, requestProto);
      } catch (Exception e) {
        transactionContext.setException(e);
      }
    }else {
      IOException exception = new IOException("plugin not found:" + pluginId);
      transactionContext.setException(exception);
    }
    return transactionContext;
  }

  @Override
	public CompletableFuture<Message> admin(Message request) {
		WrapReplyProto.Builder response = WrapReplyProto.newBuilder();
		try{
			WrapRequestProto requestProto = WrapRequestProto.parseFrom(request.getContent());
			String pluginId = requestProto.getType();
			SMPlugin smPlugin = pluginMap.get(pluginId);
			if(smPlugin!=null) {
				smPlugin.admin(requestProto, response);
			}else {
				throw new IOException("plugin not found:"+ pluginId);
			}
		} catch (Exception e) {
			response.setEx(Proto2Util.toThrowable2Proto(e));
			LOG.warn(marker, "", e);
		}
		return CompletableFuture.completedFuture(Message.valueOf(response.build()));
	}

	@Override
  public CompletableFuture<Message> query(Message request) {
    try {
			WrapRequestProto requestProto = WrapRequestProto.parseFrom(request.getContent());
			String pluginId = requestProto.getType();
      SMPlugin smPlugin = pluginMap.get(pluginId);
      if(smPlugin!=null) {
        WrapReplyProto.Builder response = WrapReplyProto.newBuilder();
        smPlugin.query(requestProto, response);
        return CompletableFuture.completedFuture(Message.valueOf(response.build()));
      }else {
        throw new IOException("plugin not found:"+ pluginId);
      }
    } catch (Exception e) {
			WrapReplyProto.Builder response = WrapReplyProto.newBuilder();
			response.setEx(Proto2Util.toThrowable2Proto(e));
      LOG.warn("", e);
			return CompletableFuture.completedFuture(Message.valueOf(response.build()));
    }
  }

	@Override
	public TransactionContext applyTransactionSerial(TransactionContext trx) throws InvalidProtocolBufferException {

    return trx;
	}

	@Override
  public CompletableFuture<Message> applyTransaction(TransactionContext trx) {
    CompletableFuture<Message> future = new CompletableFuture<>();
    ReferenceCountedObject<LogEntryProto> logEntryRef = trx.getLogEntryRef();
    LogEntryProto entry = logEntryRef.retain();
    try{
      if(updateLastAppliedTermIndex(entry.getTerm(), entry.getIndex())) {
        Message message = doApplyTransaction(entry);
        future.complete(message);
      }else{
        WrapReplyProto.Builder response = WrapReplyProto.newBuilder();
        future.complete(Message.valueOf(response.build()));
      }
    } finally {
      logEntryRef.release();
    }

    return future;
  }

  Message doApplyTransaction(LogEntryProto entry){
    WrapReplyProto.Builder response = WrapReplyProto.newBuilder();
    try {
      WrapRequestProto wrapMsgProto = WrapRequestProto.parseFrom(entry.getStateMachineLogEntry().getLogData());
      String pluginId = wrapMsgProto.getType();
      SMPlugin smPlugin = pluginMap.get(pluginId);
      if (smPlugin != null) {
        smPlugin.applyTransaction(TermIndex.valueOf(entry.getTerm(), entry.getIndex()), wrapMsgProto, response);
      } else {
        throw new SQLNonTransientException("plugin not found:" + pluginId);
      }
    } catch (Exception e) {
      response.setEx(Proto2Util.toThrowable2Proto(e));
      LOG.warn("", e);
    }
    return  Message.valueOf(response.build());
  }


	private void restoreFromSnapshot(SnapshotInfo snapshot) throws IOException {
    if(snapshot==null){
      return;
    }
    //避免有遗留的tx
    cleanTx();
    LOG.info(marker, "restore from snapshot {} files={}",snapshot.getTermIndex(), snapshot.getFiles());
    try(AutoCloseableLock writeLock = writeLock()) {
      for (SMPlugin plugin : pluginMap.values()) {
        plugin.restoreFromSnapshot(snapshot);
      }
			setLastAppliedTermIndex(snapshot.getTermIndex());
    }
		LOG.info(marker, "restore success from snapshot {} ",snapshot.getTermIndex());
	}

	@Override
	public TermIndex readySnapshot(List<FileInfo> infos) throws IOException {
		TermIndex last = null;
		try{
			try(AutoCloseableLock readLock = readLock()) {
				last = this.getLastValidTxTermIndex();
				if(last.getIndex()<0){
					return null;
				}
        Stopwatch started = Stopwatch.createStarted();
        for (SMPlugin plugin : pluginMap.values()) {
					LOG.info(marker, "readySnapshot plugin {} index={}", plugin.getId(), last.getIndex());
					List<FileInfo> fileInfos = plugin.takeSnapshot(storage, last);
					if(fileInfos!=null&&!fileInfos.isEmpty()) {
						if (!fileInfos.isEmpty()) {
							infos.addAll(fileInfos);
						}
					}
				}
        LOG.info(marker, "readySnapshot index={} time={}", last.getIndex(), started.toString());
			}

		}catch (Exception e){
			if(last.getIndex()>RaftLog.INVALID_LOG_INDEX){
				storage.cleanupSnapshot(last.getTerm(), last.getIndex());
			}
			throw e;
		}
		return last;
	}

	@Override
	public void finishSnapshot(List<FileInfo> infos, TermIndex last) throws IOException {
		if(!infos.isEmpty()) {
			try {
				for (SMPlugin plugin : pluginMap.values()) {
					LOG.info(marker, "finishSnapshot plugin {} index={}", plugin.getId(), last.getIndex());
					plugin.finishSnapshot(storage, last, infos);
				}
				File sumFile = storage.getSnapshotSumFile(last.getTerm(), last.getIndex());
				StringBuilder lines = new StringBuilder();
				infos.forEach(info -> {
					lines.append(info.getPath().getFileName()).append("\n");
				});
				try (AtomicFileOutputStream afos = new AtomicFileOutputStream(sumFile)) {
					afos.write(lines.toString().getBytes(StandardCharsets.UTF_8));
				}
				Digest digest = Md5DigestHelper.md5.computeAndSaveDigestForFile(sumFile);
				infos.add(new FileInfo(sumFile.toPath(), digest, FileListSnapshotInfo.SUM));
				storage.updateLatestSnapshot(new FileListSnapshotInfo(infos, last));
			}catch (IOException e){
				if(last.getIndex()>RaftLog.INVALID_LOG_INDEX){
					storage.cleanupSnapshot(last.getTerm(), last.getIndex());
				}
				throw e;
			}
		}
	}


  @Override
  public void close() throws IOException {
    super.close();
    if(this.scheduler!=null){
      this.scheduler.shutdown();
      this.scheduler = null;
    }
		if(executor!=null) {
			executor.shutdown();
			executor = null;
		}
    for (SMPlugin plugin : pluginMap.values()) {
      try {
        plugin.close();
      } catch (IOException e) {
        LOG.warn(marker, "", e);
      }
    }
  }

	@Override
	public RaftPeerId getLeaderId() {
		return this.leaderId.get();
	}

	@Override
  public RaftPeerId getPeerId() {
    return peerId;
  }

  @Override
  public long getCurrentTerm() {
    return Optional.ofNullable(serverStateSupport.get())
        .map(ServerStateSupport::getCurrentTerm)
        .orElse(0L);
  }

  @Override
  public boolean isLeaderReady() {
    return  Optional.ofNullable(serverStateSupport.get())
        .map(ServerStateSupport::isLeaderReady)
        .orElse(false);
  }

  @Override
  public boolean isLeader(boolean onlySelf) {
    if(onlySelf){
      return peerId.equals(getLeaderId());
    }
    return peerId.isOwner(getLeaderId());
  }

  @Override
  public ScheduledExecutorService getScheduler() {
    return scheduler;
  }

	@Override
	public ExecutorService getExecutor() {
		return executor;
	}

	@Override
  public ServerStateSupport getServerStateSupport() {
    return serverStateSupport.get();
  }


  @Override
  public PasswordEncoder getPasswordEncoder() {
    return passwordEncoder;
  }

  @Override
  public void stopServerState() {
    getServerStateSupport().stopServerState();
  }


}
