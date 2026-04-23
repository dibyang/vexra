package net.xdob.vexra.grpc.server;

import net.xdob.vexra.client.impl.ClientProtoUtils;
import net.xdob.vexra.client.impl.FastsImpl;
import net.xdob.vexra.grpc.GrpcUtil;
import net.xdob.vexra.proto.raft.*;
import net.xdob.vexra.protocol.*;
import io.grpc.stub.StreamObserver;
import net.xdob.vexra.proto.raft.RaftClientReplyProto;
import net.xdob.vexra.proto.raft.GroupManagementRequestProto;
import net.xdob.vexra.proto.grpc.AdminProtocolServiceGrpc.AdminProtocolServiceImplBase;

public class GrpcAdminProtocolService extends AdminProtocolServiceImplBase {
  private final AdminAsynchronousProtocol protocol;
  private final FastsImpl fasts = new FastsImpl();

  public GrpcAdminProtocolService(AdminAsynchronousProtocol protocol) {
    this.protocol = protocol;
  }


  @Override
  public void invokeRpc(DRpcRequestProto proto, StreamObserver<DRpcReplyProto> responseObserver) {
    try {
      DRpcRequest<Object, Object> request = ClientProtoUtils.toDRpcRequest(proto, fasts);
      GrpcUtil.asyncCall(responseObserver, () -> protocol.invokeRpcAsync(request),
          r->ClientProtoUtils.toDRpcReplyProto(r, fasts));
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void groupManagement(GroupManagementRequestProto proto,
        StreamObserver<RaftClientReplyProto> responseObserver) {
    final GroupManagementRequest request = ClientProtoUtils.toGroupManagementRequest(proto);
    GrpcUtil.asyncCall(responseObserver, () -> protocol.groupManagementAsync(request),
        ClientProtoUtils::toRaftClientReplyProto);
  }

  @Override
  public void groupList(GroupListRequestProto proto,
        StreamObserver<GroupListReplyProto> responseObserver) {
    final GroupListRequest request = ClientProtoUtils.toGroupListRequest(proto);
    GrpcUtil.asyncCall(responseObserver, () -> protocol.getGroupListAsync(request),
        ClientProtoUtils::toGroupListReplyProto);
  }

  @Override
  public void groupInfo(GroupInfoRequestProto proto, StreamObserver<GroupInfoReplyProto> responseObserver) {
    final GroupInfoRequest request = ClientProtoUtils.toGroupInfoRequest(proto);
    GrpcUtil.asyncCall(responseObserver, () -> protocol.getGroupInfoAsync(request),
        ClientProtoUtils::toGroupInfoReplyProto);
  }

  @Override
  public void setConfiguration(SetConfigurationRequestProto proto,
      StreamObserver<RaftClientReplyProto> responseObserver) {
    final SetConfigurationRequest request = ClientProtoUtils.toSetConfigurationRequest(proto);
    GrpcUtil.asyncCall(responseObserver, () -> protocol.setConfigurationAsync(request),
        ClientProtoUtils::toRaftClientReplyProto);
  }

  @Override
  public void transferLeadership(TransferLeadershipRequestProto proto,
      StreamObserver<RaftClientReplyProto> responseObserver) {
    final TransferLeadershipRequest request = ClientProtoUtils.toTransferLeadershipRequest(proto);
    GrpcUtil.asyncCall(responseObserver, () -> protocol.transferLeadershipAsync(request),
        ClientProtoUtils::toRaftClientReplyProto);
  }

  @Override
  public void snapshotManagement(SnapshotManagementRequestProto proto,
      StreamObserver<RaftClientReplyProto> responseObserver) {
    final SnapshotManagementRequest request = ClientProtoUtils.toSnapshotManagementRequest(proto);
    GrpcUtil.asyncCall(responseObserver, () -> protocol.snapshotManagementAsync(request),
        ClientProtoUtils::toRaftClientReplyProto);
  }

  @Override
	public void nodeAdmin(NodeAdminRequestProto proto, StreamObserver<RaftClientReplyProto> responseObserver) {
		final NodeAdminRequest request = ClientProtoUtils.toNodeAdminRequest(proto);
		GrpcUtil.asyncCall(responseObserver, () -> protocol.nodeAdminAsync(request),
				ClientProtoUtils::toRaftClientReplyProto);
	}

  @Override
  public void leaderElectionManagement(LeaderElectionManagementRequestProto proto,
      StreamObserver<RaftClientReplyProto> responseObserver) {
    final LeaderElectionManagementRequest request = ClientProtoUtils.toLeaderElectionManagementRequest(proto);
    GrpcUtil.asyncCall(responseObserver, () -> protocol.leaderElectionManagementAsync(request),
        ClientProtoUtils::toRaftClientReplyProto);
  }
}
