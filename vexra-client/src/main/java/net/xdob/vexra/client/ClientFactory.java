package net.xdob.vexra.client;

import net.xdob.vexra.conf.RaftProperties;
import net.xdob.vexra.protocol.ClientId;
import net.xdob.vexra.rpc.RpcFactory;

/** A factory interface for creating client components. */
public interface ClientFactory extends RpcFactory {
  static ClientFactory cast(RpcFactory rpcFactory) {
    if (rpcFactory instanceof ClientFactory) {
      return (ClientFactory)rpcFactory;
    }
    throw new ClassCastException("Cannot cast " + rpcFactory.getClass()
        + " to " + ClientFactory.class
        + "; rpc type is " + rpcFactory.getRpcType());
  }

  /** Create a {@link RaftClientRpc}. */
  RaftClientRpc newRaftClientRpc(ClientId clientId, RaftProperties properties);
}
