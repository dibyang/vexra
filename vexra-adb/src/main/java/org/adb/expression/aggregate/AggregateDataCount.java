/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.adb.expression.aggregate;

import org.adb.engine.SessionLocal;
import org.adb.value.Value;
import org.adb.value.ValueBigint;
import org.adb.value.ValueNull;

/**
 * Data stored while calculating a COUNT aggregate.
 */
final class AggregateDataCount extends AggregateData {

    private final boolean all;

    private long count;

    AggregateDataCount(boolean all) {
        this.all = all;
    }

    @Override
    void add(SessionLocal session, Value v) {
        if (all || v != ValueNull.INSTANCE) {
            count++;
        }
    }

    @Override
    Value getValue(SessionLocal session) {
        return ValueBigint.get(count);
    }

}
