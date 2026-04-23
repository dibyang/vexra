/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.adb.expression.function.table;

import org.adb.api.ErrorCode;
import org.adb.engine.SessionLocal;
import org.adb.expression.Expression;
import org.adb.message.DbException;
import org.adb.result.ResultInterface;
import org.adb.schema.FunctionAlias;

/**
 * This class wraps a user-defined function.
 */
public final class JavaTableFunction extends TableFunction {

    private final FunctionAlias functionAlias;
    private final FunctionAlias.JavaMethod javaMethod;

    public JavaTableFunction(FunctionAlias functionAlias, Expression[] args) {
        super(args);
        this.functionAlias = functionAlias;
        this.javaMethod = functionAlias.findJavaMethod(args);
        if (javaMethod.getDataType() != null) {
            throw DbException.get(ErrorCode.FUNCTION_MUST_RETURN_RESULT_SET_1, getName());
        }
    }

    @Override
    public ResultInterface getValue(SessionLocal session) {
        return javaMethod.getTableValue(session, args, false);
    }

    @Override
    public ResultInterface getValueTemplate(SessionLocal session) {
        return javaMethod.getTableValue(session, args, true);
    }

    @Override
    public void optimize(SessionLocal session) {
        super.optimize(session);
    }

    @Override
    public StringBuilder getSQL(StringBuilder builder, int sqlFlags) {
        return Expression.writeExpressions(functionAlias.getSQL(builder, sqlFlags).append('('), args, sqlFlags)
                .append(')');
    }

    @Override
    public String getName() {
        return functionAlias.getName();
    }

    @Override
    public boolean isDeterministic() {
        return functionAlias.isDeterministic();
    }

}
