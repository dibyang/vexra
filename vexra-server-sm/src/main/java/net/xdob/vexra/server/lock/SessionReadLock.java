package net.xdob.vexra.server.lock;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 组合两个读锁的实现，确保在获取两个锁时的时间计算准确性和锁的一致性
 */
public class SessionReadLock implements Lock {
	// 全局读写锁的读锁
	private final GlobeReadWriteLock.ReadLock gReadLock;
	private final ReentrantReadWriteLock.ReadLock readLock;

	public SessionReadLock(GlobeReadWriteLock.ReadLock gReadLock, ReentrantReadWriteLock.ReadLock readLock) {
		this.gReadLock = gReadLock;
		this.readLock = readLock;
	}

	@Override
	public void lock() {
		// 确保没有全局写锁
		gReadLock.lock();
		// 确保没有会话写锁
		readLock.lock();
	}

	@Override
	public void lockInterruptibly() throws InterruptedException {
		gReadLock.lockInterruptibly();
		readLock.lockInterruptibly();
	}

	@Override
	public boolean tryLock() {
		if (gReadLock.tryLock()) {
			if (readLock.tryLock()) {
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
				if (remaining > 0 && readLock.tryLock(remaining, unit)) {
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
		readLock.unlock();
	}

	@Override
	public Condition newCondition() {
		throw new UnsupportedOperationException("SessionReadLock不支持创建条件变量");
	}
}