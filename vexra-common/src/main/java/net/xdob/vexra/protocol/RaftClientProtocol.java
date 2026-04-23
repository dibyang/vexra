package net.xdob.vexra.protocol;

import java.io.IOException;

public interface RaftClientProtocol {
  RaftClientReply submitClientRequest(RaftClientRequest request) throws IOException;
}