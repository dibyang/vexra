/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.adb.expression.function;

import org.adb.engine.SessionLocal;
import org.adb.expression.Expression;
import org.adb.expression.Operation2;
import org.adb.message.DbException;
import org.adb.value.Value;
import org.adb.value.ValueNull;

/**
 * Function with two arguments.
 */
public abstract class Function2 extends Operation2 implements NamedExpression {

    protected Function2(Expression left, Expression right) {
        super(left, right);
    }

    @Override
    public Value getValue(SessionLocal session) {
        Value v1 = left.getValue(session);
        if (v1 == ValueNull.INSTANCE) {
            return ValueNull.INSTANCE;
        }
        Value v2 = right.getValue(session);
        if (v2 == ValueNull.INSTANCE) {
            return ValueNull.INSTANCE;
        }
        return getValue(session, v1, v2);
    }

    /**
     * Returns the value of this function.
     *
     * @param session
     *            the session
     * @param v1
     *            the value of first argument
     * @param v2
     *            the value of second argument
     * @return the resulting value
     */
    protected Value getValue(SessionLocal session, Value v1, Value v2) {
        throw DbException.getInternalError();
    }

    @Override
    public StringBuilder getUnenclosedSQL(StringBuilder builder, int sqlFlags) {
        left.getUnenclosedSQL(builder.append(getName()).append('('), sqlFlags).append(", ");
        return right.getUnenclosedSQL(builder, sqlFlags).append(')');
    }

}
