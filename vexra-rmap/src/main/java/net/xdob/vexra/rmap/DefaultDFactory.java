package net.xdob.vexra.rmap;

import net.xdob.vexra.client.RaftClient;
import net.xdob.vexra.client.impl.FastsImpl;
import net.xdob.vexra.conf.Parameters;
import net.xdob.vexra.conf.RaftProperties;
import net.xdob.vexra.grpc.GrpcFactory;
import net.xdob.vexra.proto.sm.WrapReplyProto;
import net.xdob.vexra.proto.sm.WrapRequestProto;
import net.xdob.vexra.protocol.*;
import net.xdob.vexra.util.IOUtils;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

public class DefaultDFactory implements DFactory, DContext{
  private final SerialSupport fasts = new FastsImpl();
  private final RaftClient client;

  public DefaultDFactory(String group, Collection<RaftPeer> peers) {
    RaftProperties raftProperties = new RaftProperties();
    final RaftGroup raftGroup = RaftGroup.valueOf(RaftGroupId.valueOf(group),
        peers);
    RaftClient.Builder builder =
        RaftClient.newBuilder().setProperties(raftProperties);
    builder.setRaftGroup(raftGroup);
    builder.setClientRpc(new GrpcFactory(new Parameters()).newRaftClientRpc(ClientId.randomId(), raftProperties));
    client = builder.build();
  }

  @Override
  public RaftClient getClient() {
    return client;
  }

  @Override
  public SerialSupport getFasts() {
    return fasts;
  }

  @Override
  public List<CacheInfo> list() throws IOException {
    GetRequest getRequest = new GetRequest()
        .setMethod(GetMethod.get);
    GetReply getReply = sendGetRequest(getRequest);
    return getReply.getData();
  }

  @Override
  public <V> RMap<V> getOrcreateRMap(String name) throws IOException {
    return new RMap<>(this, name);
  }

  @Override
  public CacheInfo drop(String name) throws IOException {
    PutRequest putRequest = new PutRequest()
        .setMethod(PutMethod.drop)
        .setName(name);
    return sendPutRequest(putRequest).getData();
  }

  public PutReply sendPutRequest(PutRequest putRequest) throws IOException {
    WrapRequestProto msgProto = WrapRequestProto.newBuilder()
        .setType(RMapSMPlugin.RMAP)
        .setBody(fasts.asByteString(putRequest))
        .build();
    RaftClientReply reply = client.io().send(Message.valueOf(msgProto));
    if(reply.getException()==null) {
      WrapReplyProto replyProto = WrapReplyProto.parseFrom(reply.getMessage().getContent());

      PutReply putReply = fasts.as(replyProto.getBody());
      if (putReply.hasEx()) {
        throw IOUtils.asIOException(putReply.getEx());
      }
      return putReply;
    }else {
      throw reply.getException();
    }
  }

  public GetReply sendGetRequest(GetRequest getRequest) throws IOException {
    WrapRequestProto msgProto = WrapRequestProto.newBuilder()
        .setType(RMapSMPlugin.RMAP)
        .setBody(fasts.asByteString(getRequest))
        .build();
    RaftClientReply reply = client.io().sendReadOnly(Message.valueOf(msgProto));
    WrapReplyProto replyProto = WrapReplyProto.parseFrom(reply.getMessage().getContent());

    GetReply getReply = fasts.as(replyProto.getBody());
    if(getReply.hasEx()){
      throw IOUtils.asIOException(getReply.getEx());
    }
    return getReply;
  }
}
