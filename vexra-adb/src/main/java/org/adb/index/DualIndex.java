/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.adb.index;

import org.adb.command.query.AllColumnsForPlan;
import org.adb.engine.SessionLocal;
import org.adb.result.Row;
import org.adb.result.SearchRow;
import org.adb.result.SortOrder;
import org.adb.table.DualTable;
import org.adb.table.IndexColumn;
import org.adb.table.TableFilter;
import org.adb.value.Value;

/**
 * An index for the DUAL table.
 */
public class DualIndex extends VirtualTableIndex {

    public DualIndex(DualTable table) {
        super(table, "DUAL_INDEX", new IndexColumn[0]);
    }

    @Override
    public Cursor find(SessionLocal session, SearchRow first, SearchRow last) {
        return new DualCursor();
    }

    @Override
    public double getCost(SessionLocal session, int[] masks, TableFilter[] filters, int filter, SortOrder sortOrder,
            AllColumnsForPlan allColumnsSet) {
        return 1d;
    }

    @Override
    public String getCreateSQL() {
        return null;
    }

    @Override
    public boolean canGetFirstOrLast() {
        return true;
    }

    @Override
    public Cursor findFirstOrLast(SessionLocal session, boolean first) {
        return new SingleRowCursor(Row.get(Value.EMPTY_VALUES, 1));
    }

    @Override
    public String getPlanSQL() {
        return "dual index";
    }

}
