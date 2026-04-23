/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.adb.index;

import org.adb.message.DbException;
import org.adb.result.Row;
import org.adb.result.SearchRow;
import org.adb.value.Value;
import org.adb.value.ValueBigint;

/**
 * The cursor implementation for the range index.
 */
class RangeCursor implements Cursor {

    private boolean beforeFirst;
    private long current;
    private Row currentRow;
    private final long start, end, step;

    RangeCursor(long start, long end, long step) {
        this.start = start;
        this.end = end;
        this.step = step;
        beforeFirst = true;
    }

    @Override
    public Row get() {
        return currentRow;
    }

    @Override
    public SearchRow getSearchRow() {
        return currentRow;
    }

    @Override
    public boolean next() {
        if (beforeFirst) {
            beforeFirst = false;
            current = start;
        } else {
            current += step;
        }
        currentRow = Row.get(new Value[]{ValueBigint.get(current)}, 1);
        return step > 0 ? current <= end : current >= end;
    }

    @Override
    public boolean previous() {
        throw DbException.getInternalError(toString());
    }

}
