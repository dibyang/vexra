/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.adb.expression;

import org.adb.engine.SessionLocal;
import org.adb.util.ParserUtil;
import org.adb.value.TypeInfo;
import org.adb.value.Value;

/**
 * A user-defined variable, for example: @ID.
 */
public final class Variable extends Operation0 {

    private final String name;
    private Value lastValue;

    public Variable(SessionLocal session, String name) {
        this.name = name;
        lastValue = session.getVariable(name);
    }

    @Override
    public int getCost() {
        return 0;
    }

    @Override
    public StringBuilder getUnenclosedSQL(StringBuilder builder, int sqlFlags) {
        return ParserUtil.quoteIdentifier(builder.append('@'), name, sqlFlags);
    }

    @Override
    public TypeInfo getType() {
        return lastValue.getType();
    }

    @Override
    public Value getValue(SessionLocal session) {
        lastValue = session.getVariable(name);
        return lastValue;
    }

    @Override
    public boolean isEverything(ExpressionVisitor visitor) {
        switch (visitor.getType()) {
        case ExpressionVisitor.DETERMINISTIC:
            return false;
        default:
            return true;
        }
    }

    public String getName() {
        return name;
    }

}
