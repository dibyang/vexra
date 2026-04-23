package net.xdob.vexra.server.lock;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.lang.IllegalMonitorStateException;

/**
 * 组合两个读锁的实现，确保在获取两个锁时的时间计算准确性和锁的一致性
 */
public class GlobeReadWriteLock {
	private final ReentrantReadWriteLock readWriteLock;
	private final ReentrantReadWriteLock.ReadLock readLock;
	private final Sync sync;

	public GlobeReadWriteLock(boolean fair) {
		this.readWriteLock = new ReentrantReadWriteLock( fair);
		this.readLock = readWriteLock.readLock();
		this.sync = new Sync();
	}

	public ReentrantReadWriteLock.WriteLock writeLock() {
		return readWriteLock.writeLock();
	}

	// 获取全局读锁
	public ReadLock globeReadLock() {
		return new ReadLock(ReadLockType.GLOBE);
	}

	// 获取会话读锁
	public ReadLock readLock() {
		return new ReadLock(ReadLockType.SESSION);
	}

	public boolean isWriteLocked(){
		return readWriteLock.isWriteLocked();
	}

	public int getReadLockCount() {
		return readWriteLock.getReadLockCount();
	}


	// 内部同步器，管理两把读锁的互斥
	private class Sync {
		private int read1Count = 0;
		private int read2Count = 0;
		private final Object mutex = new Object();

		public boolean tryAcquireReadLock(int readLockType) {
			synchronized (mutex) {
				if (readLockType == 1) {
					if (read2Count > 0) {
						return false; // 第二把读锁被持有，第一把无法获取
					}
					read1Count++;
				} else {
					if (read1Count > 0) {
						return false; // 第一把读锁被持有，第二把无法获取
					}
					read2Count++;
				}
				return true;
			}
		}

		public void releaseReadLock(int readLockType) {
			synchronized (mutex) {
				if (readLockType == 1) {
					if (read1Count <= 0) {
						throw new IllegalMonitorStateException();
					}
					read1Count--;
				} else {
					if (read2Count <= 0) {
						throw new IllegalMonitorStateException();
					}
					read2Count--;
				}
			}
		}

		public String getStateInfo() {
			synchronized (mutex) {
				return String.format("ReadLock1: %d, ReadLock2: %d", read1Count, read2Count);
			}
		}
	}

	enum ReadLockType {
		GLOBE(1),
		SESSION(2);
		private final int value;
		ReadLockType(int value) {
			this.value = value;
		}

		public int getValue() {
			return value;
		}
	}

	// 读锁实现
	public class ReadLock implements Lock {
		private final ReadLockType readLockType;

		ReadLock(ReadLockType type) {
			this.readLockType = type;
		}

		@Override
		public void lock() {
			// 先获取基础读锁（与写锁互斥）
			readLock.lock();
			try {
				// 然后检查并获取特定的读锁（与其他读锁互斥）
				synchronized (sync.mutex) {
					while (!sync.tryAcquireReadLock(readLockType.getValue())) {
						try {
							sync.mutex.wait();
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
							readLock.unlock(); // 在中断时释放基础读锁
							throw new RuntimeException("Interrupted while waiting for read lock", e);
						}
					}
				}
			} catch (Exception e) {
				try {
					sync.releaseReadLock(readLockType.getValue());
				} finally {
					readLock.unlock(); // 发生异常时释放基础读锁
				}
				throw (RuntimeException) e;
			}
		}

		@Override
		public void lockInterruptibly() throws InterruptedException {
			// 检查中断状态
			if (Thread.interrupted()) {
				throw new InterruptedException();
			}

			// 先获取基础读锁
			readLock.lockInterruptibly();
			try {
				// 然后检查并获取特定的读锁
				synchronized (sync.mutex) {
					while (!sync.tryAcquireReadLock(readLockType.getValue())) {
						sync.mutex.wait();
					}
				}
			} catch (InterruptedException e) {
				readLock.unlock(); // 中断时释放基础读锁
				throw e;
			}
		}

		@Override
		public boolean tryLock() {
			// 尝试获取基础读锁
			if (!readLock.tryLock()) {
				return false;
			}

			try {
				synchronized (sync.mutex) {
					if (sync.tryAcquireReadLock(readLockType.getValue())) {
						return true;
					} else {
						readLock.unlock(); // 无法获取特定读锁，释放基础读锁
						return false;
					}
				}
			} catch (Exception e) {
				readLock.unlock(); // 发生异常时释放基础读锁
				return false;
			}
		}

		@Override
		public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
			long nanos = unit.toNanos(time);
			long deadline = System.nanoTime() + nanos;

			// 尝试获取基础读锁（带超时）
			if (!readLock.tryLock(nanos, TimeUnit.NANOSECONDS)) {
				return false;
			}

			try {
				synchronized (sync.mutex) {
					nanos = deadline - System.nanoTime();
					while (!sync.tryAcquireReadLock(readLockType.getValue())) {
						if (nanos <= 0) {
							readLock.unlock(); // 超时，释放基础读锁
							return false;
						}
						sync.mutex.wait(nanos);
						nanos = deadline - System.nanoTime();
					}
					return true;
				}
			} catch (InterruptedException e) {
				readLock.unlock(); // 中断时释放基础读锁
				throw e;
			}
		}

		@Override
		public void unlock() {
			synchronized (sync.mutex) {
				sync.releaseReadLock(readLockType.getValue());
				sync.mutex.notifyAll(); // 通知等待的线程
			}
			readLock.unlock(); // 释放基础读锁
		}

		@Override
		public Condition newCondition() {
			throw new UnsupportedOperationException("Read locks do not support conditions");
		}
	}

	// 获取当前状态（用于测试）
	public String getStateInfo() {
		return sync.getStateInfo();
	}


}
