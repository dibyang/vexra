package net.xdob.vexra.protocol;

import net.xdob.vexra.proto.raft.SlidingWindowEntry;

public class SmClientRequest extends RaftClientRequest {
  protected SmClientRequest(Builder builder) {
    super(builder);
  }

  public static class Builder extends RaftClientRequest.Builder {

    @Override
    public SmClientRequest.Builder setClientId(ClientId clientId) {
      return  (SmClientRequest.Builder)super.setClientId(clientId);
    }

    @Override
    public SmClientRequest.Builder setLeaderId(RaftPeerId leaderId) {
      return  (SmClientRequest.Builder)super.setLeaderId(leaderId);
    }

    @Override
    public SmClientRequest.Builder setServerId(RaftPeerId serverId) {
      return  (SmClientRequest.Builder)super.setServerId(serverId);
    }

    @Override
    public SmClientRequest.Builder setGroupId(RaftGroupId groupId) {
      return  (SmClientRequest.Builder)super.setGroupId(groupId);
    }

    @Override
    public SmClientRequest.Builder setCallId(long callId) {
      return  (SmClientRequest.Builder)super.setCallId(callId);
    }

    @Override
    public SmClientRequest.Builder setRepliedCallIds(Iterable<Long> repliedCallIds) {
      return  (SmClientRequest.Builder)super.setRepliedCallIds(repliedCallIds);
    }

    @Override
    public SmClientRequest.Builder setMessage(Message message) {
      return  (SmClientRequest.Builder)super.setMessage(message);
    }

    @Override
    public SmClientRequest.Builder setType(Type type) {
      return  (SmClientRequest.Builder)super.setType(type);
    }

    @Override
    public SmClientRequest.Builder setSlidingWindowEntry(SlidingWindowEntry slidingWindowEntry) {
      return  (SmClientRequest.Builder)super.setSlidingWindowEntry(slidingWindowEntry);
    }

    @Override
    public SmClientRequest.Builder setRoutingTable(RoutingTable routingTable) {
      return (SmClientRequest.Builder)super.setRoutingTable(routingTable);
    }

    @Override
    public SmClientRequest.Builder setTimeoutMs(long timeoutMs) {
      return  (SmClientRequest.Builder)super.setTimeoutMs(timeoutMs);
    }

    public SmClientRequest build() {
      return new SmClientRequest(this);
    }
  }

  public static Builder newBuilder() {
    return new Builder();
  }


}
