package net.xdob.vexra.statemachine;

import net.xdob.vexra.proto.raft.LogEntryProto;
import net.xdob.vexra.protocol.RaftClientReply;
import net.xdob.vexra.protocol.RaftClientRequest;
import net.xdob.vexra.protocol.RaftPeerId;
import net.xdob.vexra.server.protocol.TermIndex;
import net.xdob.vexra.server.raftlog.RaftLogIOException;

import java.util.concurrent.CompletableFuture;

/**
 * 服务器状态扩展支持
 */
public interface ServerStateSupport {
  long getLastAppliedIndex();
  /**
   * 获取指定日志索引对应的 TermIndex，如果不存在返回 null。
   */
  TermIndex getTermIndex(long index);

  /**
   * 获取指定索引日志
   */
  LogEntryProto get(long index) throws RaftLogIOException;

  /**
   * 获取指定索引或比他小最接近的状态机日志
   */
  LogEntryProto getStateMachineLog(long index) throws RaftLogIOException;
  void stopServerState();

  /**
   * 获取当前 leader
   */
  RaftPeerId getLeaderId();
  /**
   * 获取当前任期
   */
  long getCurrentTerm();
  /**
   * 检查当前服务器分区是否处于准备好的领导者状态。
   * 使用场景：在 Raft 协议中，领导者需要准备好才能正常工作，调用此方法可以判断领导者是否准备好接受客户端请求。
   */
  boolean isLeaderReady();

  CompletableFuture<RaftClientReply> writeAsync(
      RaftClientRequest request);
}
