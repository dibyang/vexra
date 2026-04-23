
package net.xdob.vexra.client.impl;

import net.xdob.vexra.client.api.GroupManagementApi;
import net.xdob.vexra.protocol.GroupInfoReply;
import net.xdob.vexra.protocol.GroupInfoRequest;
import net.xdob.vexra.protocol.GroupListReply;
import net.xdob.vexra.protocol.GroupListRequest;
import net.xdob.vexra.protocol.GroupManagementRequest;
import net.xdob.vexra.protocol.RaftClientReply;
import net.xdob.vexra.protocol.RaftGroup;
import net.xdob.vexra.protocol.RaftGroupId;
import net.xdob.vexra.protocol.RaftPeerId;
import net.xdob.vexra.rpc.CallId;
import net.xdob.vexra.util.Preconditions;

import java.io.IOException;
import java.util.Objects;

class GroupManagementImpl implements GroupManagementApi {
  private final RaftPeerId server;
  private final RaftClientImpl client;

  GroupManagementImpl(RaftPeerId server, RaftClientImpl client) {
    this.server = Objects.requireNonNull(server, "server == null");
    this.client = Objects.requireNonNull(client, "client == null");
  }

  @Override
  public RaftClientReply add(RaftGroup newGroup, boolean format) throws IOException {
    Objects.requireNonNull(newGroup, "newGroup == null");

    final long callId = CallId.getAndIncrement();
    client.getClientRpc().addRaftPeers(newGroup.getPeers());
    return client.io().sendRequestWithRetry(
        () -> GroupManagementRequest.newAdd(client.getId(), server, callId, newGroup, format));
  }

  @Override
  public RaftClientReply remove(RaftGroupId groupId, boolean deleteDirectory, boolean renameDirectory)
      throws IOException {
    Objects.requireNonNull(groupId, "groupId == null");

    final long callId = CallId.getAndIncrement();
    return client.io().sendRequestWithRetry(
        () -> GroupManagementRequest.newRemove(client.getId(), server, callId, groupId,
            deleteDirectory, renameDirectory));
  }

  @Override
  public GroupListReply list() throws IOException {
    final long callId = CallId.getAndIncrement();
    final RaftClientReply reply = client.io().sendRequestWithRetry(
        () -> new GroupListRequest(client.getId(), server, client.getGroupId(), callId));
    Preconditions.assertTrue(reply instanceof GroupListReply, () -> "Unexpected reply: " + reply);
    return (GroupListReply)reply;
  }

  @Override
  public GroupInfoReply info(RaftGroupId groupId) throws IOException {
    final RaftGroupId gid = groupId != null? groupId: client.getGroupId();
    final long callId = CallId.getAndIncrement();
    final RaftClientReply reply = client.io().sendRequestWithRetry(
        () -> new GroupInfoRequest(client.getId(), server, gid, callId));
    Preconditions.assertTrue(reply instanceof GroupInfoReply, () -> "Unexpected reply: " + reply);
    return (GroupInfoReply)reply;
  }
}
