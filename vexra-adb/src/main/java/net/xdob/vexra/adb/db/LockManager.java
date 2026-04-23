package net.xdob.vexra.adb.db;

import net.xdob.vexra.adb.key.RowLockKey;
import org.adb.api.ErrorCode;
import org.adb.engine.Constants;
import org.adb.message.DbException;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class LockManager {
  private final Object monitor = new Object();
  private final Map<RowLockKey, Long> owners = new HashMap<>();
  private final Map<Long, Set<RowLockKey>> txnLocks = new HashMap<>();

  public boolean tryLock(long txnId, RowLockKey key) {
    synchronized (monitor) {
      Long owner = owners.get(key);
      if (owner == null || owner == txnId) {
        owners.put(key, txnId);
        txnLocks.computeIfAbsent(txnId, k -> new HashSet<>()).add(key);
        return true;
      }
      return false;
    }
  }

  public void lock(long txnId, RowLockKey key, long timeoutMillis) {
    long deadline = timeoutMillis < 0
        ? Long.MAX_VALUE
        : System.nanoTime() + timeoutMillis * 1_000_000L;

    synchronized (monitor) {
      while (true) {
        Long owner = owners.get(key);
        if (owner == null || owner == txnId) {
          owners.put(key, txnId);
          txnLocks.computeIfAbsent(txnId, k -> new HashSet<>()).add(key);
          return;
        }

        long now = System.nanoTime();
        if (now >= deadline) {
          throw DbException.get(ErrorCode.LOCK_TIMEOUT_1, key.toString());
        }

        long waitMillis = Math.max(1L, Math.min(
            Constants.DEADLOCK_CHECK,
            (deadline - now) / 1_000_000L
        ));

        try {
          monitor.wait(waitMillis);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw DbException.get(ErrorCode.LOCK_TIMEOUT_1, key.toString());
        }
      }
    }
  }

  public void unlockAll(long txnId) {
    synchronized (monitor) {
      Set<RowLockKey> keys = txnLocks.remove(txnId);
      if (keys != null) {
        for (RowLockKey key : keys) {
          Long owner = owners.get(key);
          if (owner != null && owner == txnId) {
            owners.remove(key);
          }
        }
      }
      monitor.notifyAll();
    }
  }
}
