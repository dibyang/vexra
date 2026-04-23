/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.adb.command.dml;

import org.adb.command.Prepared;
import org.adb.engine.SessionLocal;
import org.adb.result.ResultInterface;
import org.adb.result.ResultTarget;
import org.adb.table.DataChangeDeltaTable.ResultOption;
import org.adb.table.Table;

/**
 * Data change statement.
 */
public abstract class DataChangeStatement extends Prepared {

    private boolean isPrepared;

    /**
     * Creates new instance of DataChangeStatement.
     *
     * @param session
     *            the session
     */
    protected DataChangeStatement(SessionLocal session) {
        super(session);
    }

    @Override
    public final void prepare() {
        if (isPrepared) {
            return;
        }
        doPrepare();
        isPrepared = true;
    }

    abstract void doPrepare();

    /**
     * Return the name of this statement.
     *
     * @return the short name of this statement.
     */
    public abstract String getStatementName();

    /**
     * Return the target table.
     *
     * @return the target table
     */
    public abstract Table getTable();

    @Override
    public final boolean isTransactional() {
        return true;
    }

    @Override
    public final ResultInterface queryMeta() {
        return null;
    }

    @Override
    public boolean isCacheable() {
        return true;
    }

    @Override
    public final long update() {
        return update(null, null);
    }

    /**
     * Execute the statement with specified delta change collector and collection mode.
     *
     * @param deltaChangeCollector
     *            target result
     * @param deltaChangeCollectionMode
     *            collection mode
     * @return the update count
     */
    public abstract long update(ResultTarget deltaChangeCollector, ResultOption deltaChangeCollectionMode);

}
