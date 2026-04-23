package net.xdob.vexra.server.lock;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class SessionReadWriteLock {
	private final GlobeReadWriteLock readWriteLock;
	private final ConcurrentHashMap<String, ReentrantReadWriteLock> sessionLocks = new ConcurrentHashMap<>();
	private final boolean fair;
	public SessionReadWriteLock(boolean fair) {
		this.fair = fair;
		readWriteLock = new GlobeReadWriteLock(fair);
	}

	/**
	 * 会话读锁
	 * 互斥全局读锁，全局写锁，相同会话的会话读锁，会话写锁
	 */
	public SessionReadLock readLock(String sessionId) {
		return new SessionReadLock(readWriteLock.readLock(),
				getReadWriteLock(sessionId)
						.readLock());
	}

	private ReentrantReadWriteLock getReadWriteLock(String sessionId) {
		return sessionLocks.computeIfAbsent(sessionId,
				k -> new ReentrantReadWriteLock(fair));
	}

	/**
	 * 会话写锁
	 * 互斥全局读锁，全局写锁，相同会话的会话读锁，会话写锁
	 */
	public SessionWriteLock writeLock(String sessionId) {
		return new SessionWriteLock(readWriteLock.readLock(),
				getReadWriteLock(sessionId)
						.writeLock());
	}

	/**
	 * 全局写锁
	 * 互斥全局写锁，全局读锁，会话读锁，会话写锁
	 */
	public Lock writeLock() {
		return readWriteLock.writeLock();
	}

	/**
	 * 全局读锁
	 * 互斥全局写锁，会话读锁，会话写锁
	 */
	public Lock readLock() {
		return readWriteLock.globeReadLock();
	}

	public boolean isWriteLocked(){
		return readWriteLock.isWriteLocked();
	}

	public int getReadLockCount() {
		return readWriteLock.getReadLockCount();
	}

	/**
	 * 清理指定会话的锁资源
	 * @param sessionId 会话ID
	 */
	public void cleanupSessionLock(String sessionId) {
		if (sessionId != null) {
			sessionLocks.remove(sessionId);
		}
	}
	
	/**
	 * 清理所有会话的锁资源
	 */
	public void cleanupAllSessionLocks() {
		sessionLocks.clear();
	}
	
	/**
	 * 获取当前活跃的会话数量
	 * @return 活跃会话数量
	 */
	public int getActiveSessionCount() {
		return sessionLocks.size();
	}
}
