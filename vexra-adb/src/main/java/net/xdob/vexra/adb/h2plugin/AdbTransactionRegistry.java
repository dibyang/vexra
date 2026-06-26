package net.xdob.vexra.adb.h2plugin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.xdob.vexra.adb.db.TxnManager;
import net.xdob.vexra.adb.db.TxnMap2;
import net.xdob.vexra.adb.db.TxnState;
import org.h2.engine.Database;
import org.h2.engine.SessionLocal;

/**
 * ADB 事务上下文注册表。
 *
 * <p>旧 H2 分叉把 `TxnMap2` 直接挂在 H2 `Transaction` 上。h2db 2.3.0 通过
 * `TransactionEventProvider` 暴露 commit / rollback 边界，因此这里按 database path 和
 * session id 保存 ADB 事务上下文，并由事务事件 provider 统一提交、回滚和清理。
 */
public final class AdbTransactionRegistry {

    private static final Map<TransactionKey, TxnMap2> TXN_MAPS = new ConcurrentHashMap<>();
    private static final ThreadLocal<CurrentTransaction> CURRENT = new ThreadLocal<>();

    private AdbTransactionRegistry() {
    }

    /**
     * 获取或创建当前 session 对应的 ADB 事务上下文。
     *
     * @param session h2db session
     * @param txnManager ADB 事务管理器
     * @return ADB 事务上下文
     */
    public static TxnMap2 getOrCreate(SessionLocal session, TxnManager txnManager) {
        Database database = session.getDatabase();
        int sessionId = session.getId();
        CurrentTransaction current = CURRENT.get();
        if (current != null && current.matches(database, sessionId, txnManager)
                && isPending(current.map)) {
            return current.map;
        }

        TransactionKey key = TransactionKey.of(database, sessionId);
        TxnMap2 map = TXN_MAPS.compute(key, (ignored, existing) ->
                isPending(existing) ? existing
                        : new TxnMap2(txnManager, txnManager.beginTransaction()));
        CURRENT.set(new CurrentTransaction(database, sessionId, txnManager, key, map));
        return map;
    }

    /**
     * 提交指定 session 的 ADB 事务。
     *
     * @param database h2db database
     * @param sessionId h2db session id
     */
    public static void commit(Database database, int sessionId) {
        TxnMap2 map = currentMap(database, sessionId);
        if (map == null) {
            map = TXN_MAPS.get(TransactionKey.of(database, sessionId));
        }
        if (map != null) {
            map.commit();
        }
    }

    /**
     * 回滚指定 session 的 ADB 事务。
     *
     * @param database h2db database
     * @param sessionId h2db session id
     */
    public static void rollback(Database database, int sessionId) {
        TxnMap2 map = currentMap(database, sessionId);
        if (map == null) {
            map = TXN_MAPS.get(TransactionKey.of(database, sessionId));
        }
        if (map != null) {
            map.rollback();
        }
    }

    /**
     * 清理指定 session 的 ADB 事务上下文。
     *
     * @param database h2db database
     * @param sessionId h2db session id
     */
    public static void clear(Database database, int sessionId) {
        CurrentTransaction current = CURRENT.get();
        if (current != null && current.matches(database, sessionId)) {
            CURRENT.remove();
            TXN_MAPS.remove(current.key);
            return;
        }
        TXN_MAPS.remove(TransactionKey.of(database, sessionId));
    }

    private static boolean isPending(TxnMap2 map) {
        return map != null && TxnState.PENDING.equals(map.getTransaction().getState());
    }

    private static TxnMap2 currentMap(Database database, int sessionId) {
        CurrentTransaction current = CURRENT.get();
        if (current != null && current.matches(database, sessionId)) {
            return current.map;
        }
        return null;
    }

    private static final class CurrentTransaction {
        private final Database database;
        private final int sessionId;
        private final TxnManager txnManager;
        private final TransactionKey key;
        private final TxnMap2 map;

        private CurrentTransaction(Database database, int sessionId,
                                   TxnManager txnManager, TransactionKey key,
                                   TxnMap2 map) {
            this.database = database;
            this.sessionId = sessionId;
            this.txnManager = txnManager;
            this.key = key;
            this.map = map;
        }

        private boolean matches(Database database, int sessionId,
                                TxnManager txnManager) {
            return this.txnManager == txnManager && matches(database, sessionId);
        }

        private boolean matches(Database database, int sessionId) {
            return this.database == database && this.sessionId == sessionId;
        }
    }

    private static final class TransactionKey {
        private final String databasePath;
        private final int sessionId;

        private TransactionKey(String databasePath, int sessionId) {
            this.databasePath = databasePath;
            this.sessionId = sessionId;
        }

        static TransactionKey of(Database database, int sessionId) {
            String databasePath = database.getDatabasePath();
            if (databasePath == null) {
                databasePath = database.getName();
            }
            return new TransactionKey(databasePath == null ? "" : databasePath, sessionId);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof TransactionKey)) {
                return false;
            }
            TransactionKey other = (TransactionKey) obj;
            return sessionId == other.sessionId && databasePath.equals(other.databasePath);
        }

        @Override
        public int hashCode() {
            int result = databasePath.hashCode();
            result = 31 * result + sessionId;
            return result;
        }
    }
}
