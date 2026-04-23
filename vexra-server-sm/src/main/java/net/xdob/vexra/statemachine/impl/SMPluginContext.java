package net.xdob.vexra.statemachine.impl;

import net.xdob.vexra.protocol.Message;
import net.xdob.vexra.protocol.RaftClientReply;
import net.xdob.vexra.protocol.RaftPeerId;
import net.xdob.vexra.security.crypto.password.PasswordEncoder;
import net.xdob.vexra.server.protocol.TermIndex;
import net.xdob.vexra.statemachine.ServerStateSupport;
import net.xdob.vexra.statemachine.SnapshotInfo;
import net.xdob.vexra.util.AutoCloseableLock;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

public interface SMPluginContext {
  /**
   * 获取当前 leader
   */
	RaftPeerId getLeaderId();
  RaftPeerId getPeerId();
  /**
   * 获取当前任期
   */
  long getCurrentTerm();
  /**
   * 获取当前节点是否是leader，并且已经ready
   */
  boolean isLeaderReady();
  /**
   * 是否是leader
   * @param onlySelf 是否只判断自身,还是也包括本机其它节点
   * @return 是否是leader
   */
  boolean isLeader(boolean onlySelf);
  ScheduledExecutorService getScheduler();
	ExecutorService getExecutor();
  SnapshotInfo getLatestSnapshot();
  ServerStateSupport getServerStateSupport();
  PasswordEncoder getPasswordEncoder();
  void stopServerState();
  /**
   * 获取状态机的最新应用索引
   */
  TermIndex getLastAppliedTermIndex();
  CompletableFuture<RaftClientReply> writeLogByLeader(Message msg);
	AutoCloseableLock writeLock(String sessionId);
	AutoCloseableLock readLock(String sessionId);
	void cleanupSessionLock(String sessionId);

	/**
	 * 开启事务
   * @param sessionId 会话ID
	 */
	void beginTx(String sessionId);
	/**
	 * 结束事务
	 * @param sessionId 会话ID
	 * @param endIndex 结束事务对应索引
	 */
	void endTx(String sessionId, long endIndex);


}
