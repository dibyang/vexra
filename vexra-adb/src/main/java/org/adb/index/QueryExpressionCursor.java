/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.adb.index;

import org.adb.message.DbException;
import org.adb.result.ResultInterface;
import org.adb.result.Row;
import org.adb.result.SearchRow;
import org.adb.table.Table;
import org.adb.value.Value;
import org.adb.value.ValueNull;

/**
 * The cursor implementation of a query expression index.
 */
public class QueryExpressionCursor implements Cursor {

    private final Table table;
    private final QueryExpressionIndex index;
    private final ResultInterface result;
    private final SearchRow first, last;
    private Row current;

    public QueryExpressionCursor(QueryExpressionIndex index, ResultInterface result, SearchRow first, SearchRow last) {
        this.table = index.getTable();
        this.index = index;
        this.result = result;
        this.first = first;
        this.last = last;
    }

    @Override
    public Row get() {
        return current;
    }

    @Override
    public SearchRow getSearchRow() {
        return current;
    }

    @Override
    public boolean next() {
        while (true) {
            boolean res = result.next();
            if (!res) {
                if (index.isRecursive()) {
                    result.reset();
                } else {
                    result.close();
                }
                current = null;
                return false;
            }
            current = table.getTemplateRow();
            Value[] values = result.currentRow();
            for (int i = 0, len = current.getColumnCount(); i < len; i++) {
                Value v = i < values.length ? values[i] : ValueNull.INSTANCE;
                current.setValue(i, v);
            }
            int comp;
            if (first != null) {
                comp = index.compareRows(current, first);
                if (comp < 0) {
                    continue;
                }
            }
            if (last != null) {
                comp = index.compareRows(current, last);
                if (comp > 0) {
                    continue;
                }
            }
            return true;
        }
    }

    @Override
    public boolean previous() {
        throw DbException.getInternalError(toString());
    }

}
