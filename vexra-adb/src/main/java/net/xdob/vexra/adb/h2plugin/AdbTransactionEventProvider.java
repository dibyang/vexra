package net.xdob.vexra.adb.h2plugin;

import org.h2.api.PluginCapability;
import org.h2.api.TransactionContext;
import org.h2.api.TransactionEventProvider;

/**
 * ADB 事务生命周期监听 provider。
 *
 * <p>旧 H2 分叉把 `TxnMap2` 挂在 H2 `Transaction` 上。迁移到 h2db 后，ADB 使用
 * `TransactionEventProvider` 在 H2 commit / rollback 边界同步提交或回滚 ADB 自有事务。
 */
public final class AdbTransactionEventProvider implements TransactionEventProvider {

    public static final String ID = "adb_transaction_events";

    /**
     * 返回 provider 类型。
     *
     * @return transaction provider 类型
     */
    @Override
    public String getType() {
        return TYPE;
    }

    /**
     * 返回 provider 标识。
     *
     * @return provider id
     */
    @Override
    public String getId() {
        return ID;
    }

    /**
     * 判断当前 provider 是否支持指定能力。
     *
     * @param capability 能力名称
     * @return 支持事务事件监听时返回 true
     */
    @Override
    public boolean supports(String capability) {
        return PluginCapability.TRANSACTION_EVENTS.equals(capability);
    }

    /**
     * 在 H2 commit 前提交 ADB 事务。
     *
     * @param context 事务事件上下文
     */
    @Override
    public void beforeCommit(TransactionContext context) {
        AdbTransactionRegistry.commit(context.getDatabase(), context.getSessionId());
    }

    /**
     * 在 H2 commit 后清理 ADB 事务上下文。
     *
     * @param context 事务事件上下文
     */
    @Override
    public void afterCommit(TransactionContext context) {
        AdbTransactionRegistry.clear(context.getDatabase(), context.getSessionId());
    }

    /**
     * 在 H2 rollback 前回滚 ADB 事务。
     *
     * @param context 事务事件上下文
     */
    @Override
    public void beforeRollback(TransactionContext context) {
        AdbTransactionRegistry.rollback(context.getDatabase(), context.getSessionId());
    }

    /**
     * 在 H2 rollback 后清理 ADB 事务上下文。
     *
     * @param context 事务事件上下文
     */
    @Override
    public void afterRollback(TransactionContext context) {
        AdbTransactionRegistry.clear(context.getDatabase(), context.getSessionId());
    }
}
