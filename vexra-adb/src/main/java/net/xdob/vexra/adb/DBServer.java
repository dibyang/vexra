package net.xdob.vexra.adb;

import com.google.common.collect.Lists;

import org.adb.tools.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.List;

/**
 * DBServer
 * @author yangzj
 * @version 1.0
 */
public enum DBServer {
	adb;
  static final Logger LOG = LoggerFactory.getLogger(DBServer.class);


  private int port = 0;
	private int refCount = 0;
	private Server server;
	private final Object lock = new Object();
	public int getPort() {
		return port;
	}

  public boolean hasPort() {
    return port > 0;
  }

	public void start(int port) {
		synchronized (lock) {
			if (refCount == 0) {
				if (server == null) {
          this.port = port;
					List<String> args = Lists.newArrayList();
					args.add("-ifNotExists");
					args.add("-tcpAllowOthers");
					if (hasPort()) {
						args.add("-tcpPort");
						args.add(String.valueOf(port));
					}
					try {
						server = Server.createTcpServer(args.toArray(new String[]{}));
						server.start();
						refCount = 1;
					} catch (SQLException e) {
						LOG.error("h2 server start failed", e);
						server = null;
						refCount = 0; // 重置计数以保持状态一致性
					}
				}
			} else {
				refCount += 1;
			}
		}
	}


  public synchronized void stop(){
		synchronized (lock) {
			if(refCount<=0){
				return;
			}
			refCount-=1;
			if(refCount==0){
				if (server != null) {
					server.stop();
					server = null;
				}
			}
		}
	}
}
