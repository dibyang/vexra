/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.adb.expression.function;

import org.adb.engine.SessionLocal;
import org.adb.expression.Expression;
import org.adb.expression.TypedValueExpression;
import org.adb.message.DbException;
import org.adb.util.MathUtils;
import org.adb.util.json.JSONArray;
import org.adb.util.json.JSONValue;
import org.adb.value.TypeInfo;
import org.adb.value.Value;
import org.adb.value.ValueArray;
import org.adb.value.ValueInteger;
import org.adb.value.ValueNull;

/**
 * Cardinality expression.
 */
public final class CardinalityExpression extends Function1 {

    private final boolean max;

    /**
     * Creates new instance of cardinality expression.
     *
     * @param arg
     *            argument
     * @param max
     *            {@code false} for {@code CARDINALITY}, {@code true} for
     *            {@code ARRAY_MAX_CARDINALITY}
     */
    public CardinalityExpression(Expression arg, boolean max) {
        super(arg);
        this.max = max;
    }

    @Override
    public Value getValue(SessionLocal session) {
        int result;
        if (max) {
            TypeInfo t = arg.getType();
            if (t.getValueType() == Value.ARRAY) {
                result = MathUtils.convertLongToInt(t.getPrecision());
            } else {
                throw DbException.getInvalidValueException("array", arg.getValue(session).getTraceSQL());
            }
        } else {
            Value v = arg.getValue(session);
            if (v == ValueNull.INSTANCE) {
                return ValueNull.INSTANCE;
            }
            switch (v.getValueType()) {
            case Value.JSON: {
                JSONValue value = v.convertToAnyJson().getDecomposition();
                if (value instanceof JSONArray) {
                    result = ((JSONArray) value).length();
                } else {
                    return ValueNull.INSTANCE;
                }
                break;
            }
            case Value.ARRAY:
                result = ((ValueArray) v).getList().length;
                break;
            default:
                throw DbException.getInvalidValueException("array", v.getTraceSQL());
            }
        }
        return ValueInteger.get(result);
    }

    @Override
    public Expression optimize(SessionLocal session) {
        arg = arg.optimize(session);
        type = TypeInfo.TYPE_INTEGER;
        if (arg.isConstant()) {
            return TypedValueExpression.getTypedIfNull(getValue(session), type);
        }
        return this;
    }

    @Override
    public String getName() {
        return max ? "ARRAY_MAX_CARDINALITY" : "CARDINALITY";
    }

}
