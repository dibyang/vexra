package net.xdob.vexra.server.lock;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 组合全局读锁和会话写锁的实现，确保在获取两个锁时的时间计算准确性和锁的一致性
 */
public class SessionWriteLock implements Lock {
	// 全局写锁的读锁
	private final GlobeReadWriteLock.ReadLock gReadLock;
	private final ReentrantReadWriteLock.WriteLock writeLock;

	/**	 *
	 * @param gReadLock 全局读锁的读锁
	 * @param writeLock 会话写锁
	 */
	public SessionWriteLock(GlobeReadWriteLock.ReadLock gReadLock,
													ReentrantReadWriteLock.WriteLock writeLock) {
		this.gReadLock = gReadLock;
		this.writeLock = writeLock;
	}

	@Override
	public void lock() {
		// 确保没有全局写锁和读锁
		gReadLock.lock();
		// 确保没有会话写锁和读锁
		writeLock.lock();
	}

	@Override
	public void lockInterruptibly() throws InterruptedException {
		gReadLock.lockInterruptibly();
		writeLock.lockInterruptibly();
	}

	@Override
	public boolean tryLock() {
		if (gReadLock.tryLock()) {
			if (writeLock.tryLock()) {
				return true;
			}
			gReadLock.unlock();
		}
		return false;
	}

	@Override
	public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
		long startTime = System.nanoTime();
		if (gReadLock.tryLock(time, unit)) {
			try {
				long elapsed = unit.convert(System.nanoTime() - startTime, TimeUnit.NANOSECONDS);
				long remaining = time - elapsed;
				if (remaining > 0 && writeLock.tryLock(remaining, unit)) {
					return true;
				}
			} finally {
				gReadLock.unlock();
			}
		}
		return false;
	}

	@Override
	public void unlock() {
		gReadLock.unlock();
		writeLock.unlock();
	}

	@Override
	public Condition newCondition() {
		throw new UnsupportedOperationException("SessionWriteLock不支持创建条件变量");
	}
}