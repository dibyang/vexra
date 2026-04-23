/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.adb.command.dml;

import java.util.HashSet;

import org.adb.api.Trigger;
import org.adb.command.CommandInterface;
import org.adb.command.Prepared;
import org.adb.command.query.AllColumnsForPlan;
import org.adb.engine.DbObject;
import org.adb.engine.Right;
import org.adb.engine.SessionLocal;
import org.adb.expression.Expression;
import org.adb.expression.ExpressionVisitor;
import org.adb.message.DbException;
import org.adb.result.LocalResult;
import org.adb.result.ResultTarget;
import org.adb.result.Row;
import org.adb.table.DataChangeDeltaTable.ResultOption;
import org.adb.table.PlanItem;
import org.adb.table.Table;
import org.adb.table.TableFilter;
import org.adb.value.Value;
import org.adb.value.ValueNull;

/**
 * This class represents the statement
 * UPDATE
 */
public final class Update extends FilteredDataChangeStatement {

    private SetClauseList setClauseList;

    private Insert onDuplicateKeyInsert;

    public Update(SessionLocal session) {
        super(session);
    }

    public void setSetClauseList(SetClauseList setClauseList) {
        this.setClauseList = setClauseList;
    }

    @Override
    public long update(ResultTarget deltaChangeCollector, ResultOption deltaChangeCollectionMode) {
        targetTableFilter.startQuery(session);
        targetTableFilter.reset();
        Table table = targetTableFilter.getTable();
        try (LocalResult rows = LocalResult.forTable(session, table)) {
            session.getUser().checkTableRight(table, Right.UPDATE);
            table.fire(session, Trigger.UPDATE, true);
            table.lock(session, Table.WRITE_LOCK);
            // get the old rows, compute the new rows
            setCurrentRowNumber(0);
            long count = 0;
            long limitRows = -1;
            if (fetchExpr != null) {
                Value v = fetchExpr.getValue(session);
                if (v == ValueNull.INSTANCE || (limitRows = v.getLong()) < 0) {
                    throw DbException.getInvalidValueException("FETCH", v);
                }
            }
            while (nextRow(limitRows, count)) {
                Row oldRow = targetTableFilter.get();
                if (table.isRowLockable()) {
                    Row lockedRow = table.lockRow(session, oldRow, -1);
                    if (lockedRow == null) {
                        continue;
                    }
                    if (!oldRow.hasSharedData(lockedRow)) {
                        oldRow = lockedRow;
                        targetTableFilter.set(oldRow);
                        if (condition != null && !condition.getBooleanValue(session)) {
                            continue;
                        }
                    }
                }
                if (setClauseList.prepareUpdate(table, session, deltaChangeCollector, deltaChangeCollectionMode,
                        rows, oldRow, onDuplicateKeyInsert != null)) {
                    count++;
                }
            }
            doUpdate(this, session, table, rows);
            table.fire(session, Trigger.UPDATE, false);
            return count;
        }
    }

    static void doUpdate(Prepared prepared, SessionLocal session, Table table, LocalResult rows) {
        rows.done();
        // TODO self referencing referential integrity constraints
        // don't work if update is multi-row and 'inversed' the condition!
        // probably need multi-row triggers with 'deleted' and 'inserted'
        // at the same time. anyway good for sql compatibility
        // TODO update in-place (but if the key changes,
        // we need to update all indexes) before row triggers

        // the cached row is already updated - we need the old values
        table.updateRows(prepared, session, rows);
        if (table.fireRow()) {
            for (rows.reset(); rows.next();) {
                Row o = rows.currentRowForTable();
                rows.next();
                Row n = rows.currentRowForTable();
                table.fireAfterRow(session, o, n, false);
            }
        }
    }

    @Override
    public String getPlanSQL(int sqlFlags) {
        StringBuilder builder = new StringBuilder("UPDATE ");
        targetTableFilter.getPlanSQL(builder, false, sqlFlags);
        setClauseList.getSQL(builder, sqlFlags);
        appendFilterCondition(builder, sqlFlags);
        return builder.toString();
    }

    @Override
    void doPrepare() {
        if (condition != null) {
            condition.mapColumns(targetTableFilter, 0, Expression.MAP_INITIAL);
            condition = condition.optimizeCondition(session);
            if (condition != null) {
                condition.createIndexConditions(session, targetTableFilter);
            }
        }
        setClauseList.mapAndOptimize(session, targetTableFilter, null);
        TableFilter[] filters = new TableFilter[] { targetTableFilter };
        PlanItem item = targetTableFilter.getBestPlanItem(session, filters, 0, new AllColumnsForPlan(filters));
        targetTableFilter.setPlanItem(item);
        targetTableFilter.prepare();
    }

    @Override
    public int getType() {
        return CommandInterface.UPDATE;
    }

    @Override
    public String getStatementName() {
        return "UPDATE";
    }

    @Override
    public void collectDependencies(HashSet<DbObject> dependencies) {
        ExpressionVisitor visitor = ExpressionVisitor.getDependenciesVisitor(dependencies);
        if (condition != null) {
            condition.isEverything(visitor);
        }
        setClauseList.isEverything(visitor);
    }

    public Insert getOnDuplicateKeyInsert() {
        return onDuplicateKeyInsert;
    }

    void setOnDuplicateKeyInsert(Insert onDuplicateKeyInsert) {
        this.onDuplicateKeyInsert = onDuplicateKeyInsert;
    }

}
