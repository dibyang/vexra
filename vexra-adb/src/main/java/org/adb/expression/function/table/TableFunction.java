/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.adb.expression.function.table;

import java.util.Arrays;

import org.adb.engine.SessionLocal;
import org.adb.expression.Expression;
import org.adb.expression.ExpressionWithVariableParameters;
import org.adb.expression.function.NamedExpression;
import org.adb.message.DbException;
import org.adb.result.ResultInterface;
import org.adb.util.HasSQL;

/**
 * A table value function.
 */
public abstract class TableFunction implements HasSQL, NamedExpression, ExpressionWithVariableParameters {

    protected Expression[] args;

    private int argsCount;

    protected TableFunction(Expression[] args) {
        this.args = args;
    }

    @Override
    public void addParameter(Expression param) {
        int capacity = args.length;
        if (argsCount >= capacity) {
            args = Arrays.copyOf(args, capacity * 2);
        }
        args[argsCount++] = param;
    }

    @Override
    public void doneWithParameters() throws DbException {
        if (args.length != argsCount) {
            args = Arrays.copyOf(args, argsCount);
        }
    }

    /**
     * Get a result with.
     *
     * @param session
     *            the session
     * @return the result
     */
    public abstract ResultInterface getValue(SessionLocal session);

    /**
     * Get an empty result with the column names set.
     *
     * @param session
     *            the session
     * @return the empty result
     */
    public abstract ResultInterface getValueTemplate(SessionLocal session);

    /**
     * Try to optimize this table function
     *
     * @param session
     *            the session
     */
    public void optimize(SessionLocal session) {
        for (int i = 0, l = args.length; i < l; i++) {
            args[i] = args[i].optimize(session);
        }
    }

    /**
     * Whether the function always returns the same result for the same
     * parameters.
     *
     * @return true if it does
     */
    public abstract boolean isDeterministic();

    @Override
    public StringBuilder getSQL(StringBuilder builder, int sqlFlags) {
        return Expression.writeExpressions(builder.append(getName()).append('('), args, sqlFlags).append(')');
    }

}
