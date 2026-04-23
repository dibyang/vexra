

package net.xdob.vexra.client.impl;

import net.xdob.vexra.client.api.LeaderElectionManagementApi;
import net.xdob.vexra.protocol.LeaderElectionManagementRequest;
import net.xdob.vexra.protocol.RaftClientReply;
import net.xdob.vexra.protocol.RaftPeerId;
import net.xdob.vexra.rpc.CallId;

import java.io.IOException;
import java.util.Objects;

public class LeaderElectionManagementImpl implements LeaderElectionManagementApi {

  private final RaftClientImpl client;
  private final RaftPeerId server;

  LeaderElectionManagementImpl(RaftPeerId server, RaftClientImpl client) {
    this.server =  Objects.requireNonNull(server, "server == null");
    this.client = Objects.requireNonNull(client, "client == null");
  }
  @Override
  public RaftClientReply pause() throws IOException {
    final long callId = CallId.getAndIncrement();
    return client.io().sendRequestWithRetry(() -> LeaderElectionManagementRequest.newPause(client.getId(),
        server, client.getGroupId(), callId));
  }

  @Override
  public RaftClientReply resume() throws IOException {
    final long callId = CallId.getAndIncrement();
    return client.io().sendRequestWithRetry(() -> LeaderElectionManagementRequest.newResume(client.getId(),
        server, client.getGroupId(), callId));
  }
}
