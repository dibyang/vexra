/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.adb.mode;

import org.adb.api.ErrorCode;
import org.adb.engine.SessionLocal;
import org.adb.expression.Expression;
import org.adb.expression.Operation1;
import org.adb.expression.ValueExpression;
import org.adb.index.Index;
import org.adb.message.DbException;
import org.adb.schema.Schema;
import org.adb.table.Table;
import org.adb.value.TypeInfo;
import org.adb.value.Value;
import org.adb.value.ValueInteger;
import org.adb.value.ValueNull;

/**
 * A ::regclass expression.
 */
public final class Regclass extends Operation1 {

    public Regclass(Expression arg) {
        super(arg);
    }

    @Override
    public Value getValue(SessionLocal session) {
        Value value = arg.getValue(session);
        if (value == ValueNull.INSTANCE) {
            return ValueNull.INSTANCE;
        }
        int valueType = value.getValueType();
        if (valueType >= Value.TINYINT && valueType <= Value.INTEGER) {
            return value.convertToInt(null);
        }
        if (valueType == Value.BIGINT) {
            return ValueInteger.get((int) value.getLong());
        }
        String name = value.getString();
        for (Schema schema : session.getDatabase().getAllSchemas()) {
            Table table = schema.findTableOrView(session, name);
            if (table != null && !table.isHidden()) {
                return ValueInteger.get(table.getId());
            }
            Index index = schema.findIndex(session, name);
            if (index != null && index.getCreateSQL() != null) {
                return ValueInteger.get(index.getId());
            }
        }
        throw DbException.get(ErrorCode.TABLE_OR_VIEW_NOT_FOUND_1, name);
    }

    @Override
    public TypeInfo getType() {
        return TypeInfo.TYPE_INTEGER;
    }

    @Override
    public Expression optimize(SessionLocal session) {
        arg = arg.optimize(session);
        if (arg.isConstant()) {
            return ValueExpression.get(getValue(session));
        }
        return this;
    }

    @Override
    public StringBuilder getUnenclosedSQL(StringBuilder builder, int sqlFlags) {
        return arg.getSQL(builder, sqlFlags, AUTO_PARENTHESES).append("::REGCLASS");
    }

    @Override
    public int getCost() {
        return arg.getCost() + 100;
    }

}
