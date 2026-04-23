/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.adb.expression.condition;

import org.adb.command.query.Query;
import org.adb.engine.SessionLocal;
import org.adb.value.Value;
import org.adb.value.ValueBoolean;

/**
 * Exists predicate as in EXISTS(SELECT ...)
 */
public class ExistsPredicate extends PredicateWithSubquery {

    public ExistsPredicate(Query query) {
        super(query);
    }

    @Override
    public Value getValue(SessionLocal session) {
        query.setSession(session);
        return ValueBoolean.get(query.exists());
    }

    @Override
    public StringBuilder getUnenclosedSQL(StringBuilder builder, int sqlFlags) {
        return super.getUnenclosedSQL(builder.append("EXISTS"), sqlFlags);
    }

}
