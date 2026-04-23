package net.xdob.vexra.rmap;

import net.xdob.vexra.client.RaftClient;
import net.xdob.vexra.protocol.SerialSupport;

import java.io.IOException;

public interface DContext {
  RaftClient getClient();
  SerialSupport getFasts();
  PutReply sendPutRequest(PutRequest putRequest) throws IOException;
  GetReply sendGetRequest(GetRequest getRequest) throws IOException;
}
