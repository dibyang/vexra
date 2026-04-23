package net.xdob.vexra.protocol;

import net.xdob.vexra.proto.raft.CommitInfoProto;

import java.util.Collection;

public interface DataStreamReply extends DataStreamPacket {

  boolean isSuccess();

  long getBytesWritten();

  /** @return the commit information when the reply is created. */
  Collection<CommitInfoProto> getCommitInfos();
}