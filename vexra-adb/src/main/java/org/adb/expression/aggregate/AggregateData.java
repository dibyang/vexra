/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.adb.expression.aggregate;

import org.adb.engine.SessionLocal;
import org.adb.value.Value;

/**
 * Abstract class for the computation of an aggregate.
 */
abstract class AggregateData {

    /**
     * Add a value to this aggregate.
     *
     * @param session the session
     * @param v the value
     */
    abstract void add(SessionLocal session, Value v);

    /**
     * Get the aggregate result.
     *
     * @param session the session
     * @return the value
     */
    abstract Value getValue(SessionLocal session);

}
