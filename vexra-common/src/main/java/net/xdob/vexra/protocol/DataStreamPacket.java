package net.xdob.vexra.protocol;

import net.xdob.vexra.proto.raft.DataStreamPacketHeaderProto.Type;

public interface DataStreamPacket {
  ClientId getClientId();

  Type getType();

  long getStreamId();

  long getStreamOffset();

  long getDataLength();
}