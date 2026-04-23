package net.xdob.vexra.protocol;

import net.xdob.vexra.datastream.impl.DataStreamPacketImpl;
import net.xdob.vexra.proto.raft.DataStreamPacketHeaderProto.Type;
import net.xdob.vexra.util.SizeInBytes;

/** The header format is streamId, streamOffset, dataLength. */
public class DataStreamPacketHeader extends DataStreamPacketImpl {
  private static final SizeInBytes SIZE_OF_HEADER_LEN = SizeInBytes.valueOf(4);
  private static final SizeInBytes SIZE_OF_HEADER_BODY_LEN = SizeInBytes.valueOf(8);

  private final long dataLength;

  public DataStreamPacketHeader(ClientId clientId, Type type, long streamId, long streamOffset, long dataLength) {
    super(clientId, type, streamId, streamOffset);
    this.dataLength = dataLength;
  }

  @Override
  public long getDataLength() {
    return dataLength;
  }

  public static int getSizeOfHeaderLen() {
    return SIZE_OF_HEADER_LEN.getSizeInt();
  }

  public static int getSizeOfHeaderBodyLen() {
    return SIZE_OF_HEADER_BODY_LEN.getSizeInt();
  }
}