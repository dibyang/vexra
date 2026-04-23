/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.adb.command.dml;

import org.adb.command.CommandInterface;
import org.adb.command.Prepared;
import org.adb.engine.SessionLocal;
import org.adb.expression.Alias;
import org.adb.expression.Expression;
import org.adb.expression.ExpressionColumn;
import org.adb.expression.ExpressionVisitor;
import org.adb.expression.function.table.TableFunction;
import org.adb.result.LocalResult;
import org.adb.result.ResultInterface;
import org.adb.table.Column;
import org.adb.value.Value;

/**
 * This class represents the statement
 * CALL.
 */
public class Call extends Prepared {

    private Expression expression;

    private TableFunction tableFunction;

    private Expression[] expressions;

    public Call(SessionLocal session) {
        super(session);
    }

    @Override
    public ResultInterface queryMeta() {
        int columnCount = expressions.length;
        LocalResult result = new LocalResult(session, expressions, columnCount, columnCount);
        result.done();
        return result;
    }

    @Override
    public long update() {
        if (tableFunction != null) {
            // this will throw an exception
            // methods returning a result set may not be called like this.
            return super.update();
        }
        Value v = expression.getValue(session);
        int type = v.getValueType();
        switch (type) {
        case Value.UNKNOWN:
        case Value.NULL:
            return 0;
        default:
            return v.getInt();
        }
    }

    @Override
    public ResultInterface query(long maxrows) {
        setCurrentRowNumber(1);
        if (tableFunction != null) {
            return tableFunction.getValue(session);
        }
        LocalResult result = new LocalResult(session, expressions, 1, 1);
        result.addRow(expression.getValue(session));
        result.done();
        return result;
    }

    @Override
    public void prepare() {
        if (tableFunction != null) {
            prepareAlways = true;
            tableFunction.optimize(session);
            ResultInterface result = tableFunction.getValueTemplate(session);
            int columnCount = result.getVisibleColumnCount();
            expressions = new Expression[columnCount];
            for (int i = 0; i < columnCount; i++) {
                String name = result.getColumnName(i);
                String alias = result.getAlias(i);
                Expression e = new ExpressionColumn(getDatabase(), new Column(name, result.getColumnType(i)));
                if (!alias.equals(name)) {
                    e = new Alias(e, alias, false);
                }
                expressions[i] = e;
            }
        } else {
            expressions = new Expression[] { expression = expression.optimize(session) };
        }
    }

    public void setExpression(Expression expression) {
        this.expression = expression;
    }

    public void setTableFunction(TableFunction tableFunction) {
        this.tableFunction = tableFunction;
    }

    @Override
    public boolean isQuery() {
        return true;
    }

    @Override
    public boolean isTransactional() {
        return true;
    }

    @Override
    public boolean isReadOnly() {
        return tableFunction == null && expression.isEverything(ExpressionVisitor.READONLY_VISITOR);

    }

    @Override
    public int getType() {
        return CommandInterface.CALL;
    }

    @Override
    public boolean isCacheable() {
        return tableFunction == null;
    }

}
