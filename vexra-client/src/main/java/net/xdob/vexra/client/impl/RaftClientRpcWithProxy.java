
package net.xdob.vexra.client.impl;

import net.xdob.vexra.client.RaftClientRpc;
import net.xdob.vexra.protocol.RaftPeer;
import net.xdob.vexra.protocol.RaftPeerId;
import net.xdob.vexra.util.PeerProxyMap;

import java.io.Closeable;
import java.util.Collection;

/** An abstract {@link RaftClientRpc} implementation using {@link PeerProxyMap}. */
public abstract class RaftClientRpcWithProxy<PROXY extends Closeable>
    implements RaftClientRpc {
  private final PeerProxyMap<PROXY> proxies;

  protected RaftClientRpcWithProxy(PeerProxyMap<PROXY> proxies) {
    this.proxies = proxies;
  }

  public PeerProxyMap<PROXY> getProxies() {
    return proxies;
  }

  @Override
  public void addRaftPeers(Collection<RaftPeer> servers) {
    proxies.addRaftPeers(servers);
  }

  @Override
  public boolean handleException(RaftPeerId serverId, Throwable t, boolean reconnect) {
    return getProxies().handleException(serverId, t, reconnect);
  }

  @Override
  public void close() {
    proxies.close();
  }
}
