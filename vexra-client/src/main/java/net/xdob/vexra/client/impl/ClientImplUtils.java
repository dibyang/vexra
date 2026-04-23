
package net.xdob.vexra.client.impl;

import net.xdob.vexra.client.DataStreamClient;
import net.xdob.vexra.client.DataStreamClientRpc;
import net.xdob.vexra.client.RaftClient;
import net.xdob.vexra.client.RaftClientRpc;
import net.xdob.vexra.conf.Parameters;
import net.xdob.vexra.conf.RaftProperties;
import net.xdob.vexra.protocol.RaftGroup;
import net.xdob.vexra.protocol.RaftGroupId;
import net.xdob.vexra.protocol.RaftPeer;
import net.xdob.vexra.retry.RetryPolicy;
import net.xdob.vexra.protocol.ClientId;
import net.xdob.vexra.protocol.RaftPeerId;

/** Client utilities for internal use. */
public interface ClientImplUtils {
  @SuppressWarnings("checkstyle:ParameterNumber")
  static RaftClient newRaftClient(ClientId clientId, RaftGroup group,
      RaftPeerId leaderId, RaftPeer primaryDataStreamServer, RaftClientRpc clientRpc, RetryPolicy retryPolicy,
      RaftProperties properties, Parameters parameters) {
    return new RaftClientImpl(clientId, group, leaderId, primaryDataStreamServer, clientRpc, retryPolicy,
        properties, parameters);
  }

  static DataStreamClient newDataStreamClient(ClientId clientId, RaftGroupId groupId, RaftPeer primaryDataStreamServer,
      DataStreamClientRpc dataStreamClientRpc, RaftProperties properties) {
    return new DataStreamClientImpl(clientId, groupId, primaryDataStreamServer, dataStreamClientRpc, properties);
  }

  static DataStreamClient newDataStreamClient(RaftClient client, RaftPeer primaryDataStreamServer,
      DataStreamClientRpc dataStreamClientRpc, RaftProperties properties) {
    return new DataStreamClientImpl(client, primaryDataStreamServer, dataStreamClientRpc, properties);
  }
}
