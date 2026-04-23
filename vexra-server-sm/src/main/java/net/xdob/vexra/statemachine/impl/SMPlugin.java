package net.xdob.vexra.statemachine.impl;

import com.google.common.collect.Lists;
import net.xdob.vexra.proto.sm.WrapReplyProto;
import net.xdob.vexra.proto.sm.WrapRequestProto;
import net.xdob.vexra.protocol.RaftGroupId;
import net.xdob.vexra.protocol.RaftPeerId;
import net.xdob.vexra.server.RaftServer;
import net.xdob.vexra.server.protocol.TermIndex;
import net.xdob.vexra.server.storage.FileInfo;
import net.xdob.vexra.server.storage.RaftStorage;
import net.xdob.vexra.statemachine.SnapshotInfo;
import net.xdob.vexra.statemachine.TransactionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

public interface SMPlugin extends Closeable {
  Logger LOG = LoggerFactory.getLogger(SMPlugin.class);
  String getId();
  void initialize(RaftServer server, RaftGroupId groupId, RaftPeerId peerId, RaftStorage raftStorage) throws IOException;

  void setSMPluginContext(SMPluginContext context);

  default void reinitialize() throws IOException{

  }

  default void startTransaction(TransactionContext transactionContext, WrapRequestProto request)
      throws SQLException {

  }

	default void admin(WrapRequestProto request, WrapReplyProto.Builder reply) {

	}

  default void query(WrapRequestProto request, WrapReplyProto.Builder reply) {

  }

  default void applyTransaction(TermIndex termIndex, WrapRequestProto request, WrapReplyProto.Builder reply)  {

  }


	/**
	 * 快照生成
	 */

  default List<FileInfo> takeSnapshot(FileListStateMachineStorage storage, TermIndex last) throws IOException{
    return Lists.newArrayList();
  }

	/**
	 * 此方法不再持有读锁
	 * 完成快照的后继处理，比如数据校验
	 */
	default void finishSnapshot(FileListStateMachineStorage storage, TermIndex last, List<FileInfo>  files) throws IOException{
	}

  default void restoreFromSnapshot(SnapshotInfo snapshot) throws IOException{

  }

}
