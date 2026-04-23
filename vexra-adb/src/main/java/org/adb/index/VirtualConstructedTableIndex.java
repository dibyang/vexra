/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.adb.index;

import org.adb.command.query.AllColumnsForPlan;
import org.adb.engine.SessionLocal;
import org.adb.message.DbException;
import org.adb.result.SearchRow;
import org.adb.result.SortOrder;
import org.adb.table.FunctionTable;
import org.adb.table.IndexColumn;
import org.adb.table.TableFilter;
import org.adb.table.VirtualConstructedTable;

/**
 * An index for a virtual table that returns a result set. Search in this index
 * performs scan over all rows and should be avoided.
 */
public class VirtualConstructedTableIndex extends VirtualTableIndex {

    private final VirtualConstructedTable table;

    public VirtualConstructedTableIndex(VirtualConstructedTable table, IndexColumn[] columns) {
        super(table, null, columns);
        this.table = table;
    }

    @Override
    public boolean isFindUsingFullTableScan() {
        return true;
    }

    @Override
    public Cursor find(SessionLocal session, SearchRow first, SearchRow last) {
        return new VirtualTableCursor(this, first, last, table.getResult(session));
    }

    @Override
    public double getCost(SessionLocal session, int[] masks, TableFilter[] filters, int filter, SortOrder sortOrder,
            AllColumnsForPlan allColumnsSet) {
        if (masks != null) {
            throw DbException.getUnsupportedException("Virtual table");
        }
        long expectedRows;
        if (table.canGetRowCount(session)) {
            expectedRows = table.getRowCountApproximation(session);
        } else {
            expectedRows = database.getSettings().estimatedFunctionTableRows;
        }
        return expectedRows * 10;
    }

    @Override
    public String getPlanSQL() {
        return table instanceof FunctionTable ? "function" : "table scan";
    }

    @Override
    public boolean canScan() {
        return false;
    }

}
