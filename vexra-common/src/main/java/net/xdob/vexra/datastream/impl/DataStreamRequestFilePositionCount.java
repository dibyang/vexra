package net.xdob.vexra.datastream.impl;

import net.xdob.vexra.io.FilePositionCount;
import net.xdob.vexra.io.WriteOption;
import net.xdob.vexra.protocol.DataStreamRequest;
import net.xdob.vexra.protocol.DataStreamRequestHeader;

import java.util.List;

/**
 * Implements {@link DataStreamRequest} with {@link FilePositionCount}.
 * <p>
 * This class is immutable.
 */
public class DataStreamRequestFilePositionCount extends DataStreamPacketImpl implements DataStreamRequest {
  private final FilePositionCount file;
  private final List<WriteOption> options;

  public DataStreamRequestFilePositionCount(DataStreamRequestHeader header, FilePositionCount file) {
    super(header.getClientId(), header.getType(), header.getStreamId(), header.getStreamOffset());
    this.options = header.getWriteOptionList();
    this.file = file;
  }

  @Override
  public long getDataLength() {
    return file.getCount();
  }

  /** @return the file with the starting position and the byte count. */
  public FilePositionCount getFile() {
    return file;
  }

  @Override
  public List<WriteOption> getWriteOptionList() {
    return options;
  }
}
