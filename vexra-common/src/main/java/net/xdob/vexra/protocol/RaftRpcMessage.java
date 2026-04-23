package net.xdob.vexra.protocol;

public interface RaftRpcMessage {

  boolean isRequest();

  String getRequestorId();

  String getReplierId();

  RaftGroupId getRaftGroupId();
}
