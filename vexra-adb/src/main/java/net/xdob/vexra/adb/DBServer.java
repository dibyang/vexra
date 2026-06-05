package net.xdob.vexra.adb;

import com.google.common.collect.Lists;

import org.h2.tools.Server;
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
  /**
   * 返回当前配置的 TCP 端口。
   *
   * @return TCP 端口，尚未配置时返回 0
   */
  public int getPort() {
		return port;
	}

  /**
   * 判断当前是否配置了 TCP 端口。
   *
   * @return 已配置端口时返回 true
   */
  public boolean hasPort() {
    return port > 0;
  }

  /**
   * 启动 h2db TCP Server。
   *
   * @param port TCP 监听端口
   */
	public void start(int port) {
		synchronized (lock) {
			if (refCount == 0) {
				if (server == null) {
          this.port = port;
					List<String> args = Lists.newArrayList();
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
						throw new IllegalStateException("h2 server start failed", e);
					}
				}
			} else {
				refCount += 1;
			}
		}
	}


  /**
   * 判断底层 h2db Server 是否正在运行。
   *
   * @return server 已启动并可接受连接时返回 true
   */
  public boolean isRunning() {
    synchronized (lock) {
      return server != null;
    }
  }

  /**
   * 释放一次 server 引用，并在引用计数归零时停止 server。
   */
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
