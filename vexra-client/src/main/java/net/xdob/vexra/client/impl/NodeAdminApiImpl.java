package net.xdob.vexra.client.impl;

import net.xdob.vexra.client.api.NodeAdminApi;
import net.xdob.vexra.protocol.RaftClientReply;
import net.xdob.vexra.protocol.RaftPeerId;
import net.xdob.vexra.protocol.NodeAdminRequest;
import net.xdob.vexra.rpc.CallId;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

public class NodeAdminApiImpl implements NodeAdminApi {
	private final RaftClientImpl client;
	private final RaftPeerId server;

	NodeAdminApiImpl(RaftPeerId server, RaftClientImpl client) {
		this.server = server;
		this.client = Objects.requireNonNull(client, "client == null");
	}


	@Override
	public RaftClientReply suspend(long timeoutMs) throws IOException {
		final long callId = CallId.getAndIncrement();
		return client.io().sendRequestWithRetry(() -> NodeAdminRequest.newSuspend(client.getId(),
				Optional.ofNullable(server).orElseGet(client::getLeaderId),
				client.getGroupId(), callId, timeoutMs));
	}

	@Override
	public RaftClientReply resume(long timeoutMs) throws IOException {
		final long callId = CallId.getAndIncrement();
		return client.io().sendRequestWithRetry(() -> NodeAdminRequest.newResume(client.getId(),
				Optional.ofNullable(server).orElseGet(client::getLeaderId),
				client.getGroupId(), callId, timeoutMs));
	}



	@Override
	public RaftClientReply status(long timeoutMs) throws IOException {
		final long callId = CallId.getAndIncrement();
		return client.io().sendRequestWithRetry(() -> NodeAdminRequest.newStatus(client.getId(),
				Optional.ofNullable(server).orElseGet(client::getLeaderId),
				client.getGroupId(), callId, timeoutMs));
	}
}
