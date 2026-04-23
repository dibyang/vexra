package net.xdob.vexra.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 表级别锁管理器
 * 锁只与表名相关，不同会话之间互斥，同一会话内可重入
 */
public class TableLockManager {
  static final Logger LOG = LoggerFactory.getLogger(TableLockManager.class);

  // 表名 -> 表锁
  private final ConcurrentHashMap<String, TableLock> tableLocks = new ConcurrentHashMap<>();

  /**
   * 表锁 - 与表名绑定
   */
  public static class TableLock {
    private final String tableName;
    private final Object mutex = new Object();

    // 当前持有锁的会话ID
    private volatile String holderSessionId;

    // 重入计数
    private int holdCount = 0;

    // 记录当前持有锁的会话内的所有线程
    private final ConcurrentHashMap<Thread, Integer> threadHoldCount = new ConcurrentHashMap<>();

    // 等待中的会话
    private final ConcurrentHashMap<String, AtomicInteger> waitingSessions = new ConcurrentHashMap<>();

    public TableLock(String tableName) {
      this.tableName = tableName;
    }

    public String getTableName() {
      return tableName;
    }

    /**
     * 获取锁（阻塞直到成功）
     */
    public void lock(String sessionId) {
      if (sessionId == null || sessionId.trim().isEmpty()) {
        throw new IllegalArgumentException("Session ID不能为空");
      }

      synchronized (mutex) {
        // 如果锁未被持有，直接获取
        if (holderSessionId == null) {
          acquireLock(sessionId);
          return;
        }

        // 如果锁被同一会话持有，可重入
        if (holderSessionId.equals(sessionId)) {
          holdCount++;
          threadHoldCount.merge(Thread.currentThread(), 1, Integer::sum);
          return;
        }

        // 锁被其他会话持有，等待
        try {
          // 记录等待的会话
          waitingSessions.computeIfAbsent(sessionId, k -> new AtomicInteger()).incrementAndGet();

          while (holderSessionId != null && !holderSessionId.equals(sessionId)) {
            mutex.wait();
          }

          waitingSessions.computeIfPresent(sessionId, (k, v) -> {
            if (v.decrementAndGet() == 0) return null;
            return v;
          });

          acquireLock(sessionId);

        } catch (InterruptedException e) {
          waitingSessions.computeIfPresent(sessionId, (k, v) -> {
            if (v.decrementAndGet() == 0) return null;
            return v;
          });
          Thread.currentThread().interrupt();
          throw new RuntimeException("获取表锁被中断: " + tableName, e);
        }
      }
    }

    /**
     * 尝试获取锁（非阻塞）
     */
    public boolean tryLock(String sessionId) {
      if (sessionId == null || sessionId.trim().isEmpty()) {
        throw new IllegalArgumentException("Session ID不能为空");
      }

      synchronized (mutex) {
        if (holderSessionId == null) {
          acquireLock(sessionId);
          return true;
        }

        if (holderSessionId.equals(sessionId)) {
          holdCount++;
          threadHoldCount.merge(Thread.currentThread(), 1, Integer::sum);
          return true;
        }

        return false;
      }
    }

    /**
     * 尝试获取锁（带超时）- 使用 nanoTime
     */
    public boolean tryLock(String sessionId, long timeout, TimeUnit unit)
        throws InterruptedException {
      if (sessionId == null || sessionId.trim().isEmpty()) {
        throw new IllegalArgumentException("Session ID不能为空");
      }

      long timeoutMs = unit.toMillis(timeout);
      Timestamp startTime = Timestamp.currentTime();

      synchronized (mutex) {
        if (Thread.interrupted()) {
          throw new InterruptedException();
        }

        while (true) {
          if (holderSessionId == null) {
            acquireLock(sessionId);
            return true;
          }

          if (holderSessionId.equals(sessionId)) {
            holdCount++;
            threadHoldCount.merge(Thread.currentThread(), 1, Integer::sum);
            return true;
          }

          long elapsedMs = startTime.elapsedTimeMs();
          long remainingMS = timeoutMs - elapsedMs;

          if (remainingMS <= 0) {
            return false;  // 超时
          }

          waitingSessions.computeIfAbsent(sessionId, k -> new AtomicInteger()).incrementAndGet();
          try {
            mutex.wait(remainingMS);
          } finally {
            waitingSessions.computeIfPresent(sessionId, (k, v) -> {
              if (v.decrementAndGet() == 0) return null;
              return v;
            });
          }

          if (Thread.interrupted()) {
            throw new InterruptedException();
          }
        }
      }
    }

    /**
     * 释放锁
     */
    public void unlock(String sessionId) {
      if (sessionId == null || sessionId.trim().isEmpty()) {
        throw new IllegalArgumentException("Session ID不能为空");
      }

      synchronized (mutex) {
        // 验证锁状态
        if (holderSessionId == null) {
          throw new IllegalStateException(String.format(
              "表锁 %s 未被持有", tableName));
        }

        // 验证会话权限 - 只能释放自己会话的锁
        if (!holderSessionId.equals(sessionId)) {
          throw new IllegalStateException(String.format(
              "会话 %s 不能释放会话 %s 持有的表锁 %s",
              sessionId, holderSessionId, tableName));
        }

        // 减少当前线程的计数
        Integer threadCount = threadHoldCount.get(Thread.currentThread());
        if (threadCount != null) {
          if (threadCount == 1) {
            threadHoldCount.remove(Thread.currentThread());
          } else {
            threadHoldCount.put(Thread.currentThread(), threadCount - 1);
          }
        }

        // 减少重入计数
        holdCount--;

        if (holdCount == 0) {
          // 完全释放锁
          holderSessionId = null;
          threadHoldCount.clear();
          mutex.notifyAll();
        }
      }
    }

    void forceUnlock(){
      synchronized (mutex) {
        this.holderSessionId = null;
        this.holdCount = 0;
        this.threadHoldCount.clear();
        mutex.notifyAll();
      }
    }


    /**
     * 获取锁的内部方法
     */
    private void acquireLock(String sessionId) {
      this.holderSessionId = sessionId;
      this.holdCount = 1;
      this.threadHoldCount.put(Thread.currentThread(), 1);
    }

    // ========== 查询方法 ==========

    public boolean isLocked() {
      return holderSessionId != null;
    }

    public boolean isHeldBySession(String sessionId) {
      return holderSessionId != null && holderSessionId.equals(sessionId);
    }

    public String getHolderSessionId() {
      return holderSessionId;
    }

    public int getHoldCount(String sessionId) {
      if (!isHeldBySession(sessionId)) {
        throw new IllegalStateException("会话未持有此锁");
      }
      return holdCount;
    }

    public int getWaitingSessionCount() {
      return waitingSessions.size();
    }

    public int getThreadHoldCount() {
      return threadHoldCount.size();
    }

    @Override
    public String toString() {
      synchronized (mutex) {
        if (holderSessionId == null) {
          return String.format("TableLock[%s] - 未锁定", tableName);
        }
        return String.format("TableLock[%s] - 持有者:%s, 重入:%d, 线程数:%d, 等待会话:%s",
            tableName, holderSessionId, holdCount,
            threadHoldCount.size(), waitingSessions.keySet());
      }
    }
  }

  /**
   * 获取表的锁
   */
  public TableLock getTableLock(String tableName) {
    tableName = handleTableName(tableName);
    return tableLocks.computeIfAbsent(tableName, TableLock::new);
  }

  private static String handleTableName(String tableName) {
    if (tableName == null || tableName.trim().isEmpty()) {
      throw new IllegalArgumentException("表名不能为空");
    }
    tableName = tableName.toUpperCase();
    return tableName;
  }

  /**
   * 释放表锁
   */
  public void unlock(String sessionId, String tableName) {
    tableName = handleTableName(tableName);
    TableLock lock = tableLocks.get(tableName);
    if (lock == null) {
      throw new IllegalStateException("表锁不存在: " + tableName);
    }
    lock.unlock(sessionId);
  }

  /**
   * 移除未使用的表锁
   */
  public boolean removeIfUnused(String tableName) {
    tableName = handleTableName(tableName);
    TableLock lock = tableLocks.get(tableName);
    if (lock != null && !lock.isLocked()) {
      return tableLocks.remove(tableName, lock);
    }
    return false;
  }

  /**
   * 获取所有被锁定的表
   */
  public ConcurrentHashMap<String, TableLock> getLockedTables() {
    return tableLocks;
  }

  public void clear(){
    for (TableLock lock : tableLocks.values()) {
      lock.forceUnlock();
    }
    tableLocks.clear();
  }

  public void clearSession(String sessionId){
    List<TableLock> locks = tableLocks.values().stream().filter(lock -> lock.isHeldBySession(sessionId))
        .collect(Collectors.toList());
    for (TableLock lock : locks) {
      tableLocks.remove(lock.getTableName());
      lock.forceUnlock();
    }
  }

  /**
   * 打印锁状态
   */
  void printLockStatus() {
    LOG.info("=== 表锁状态 ===");
    tableLocks.forEach((tableName, lock) -> {
      LOG.info(lock.toString());
    });
  }
}