/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.adb.expression.aggregate;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

import org.adb.api.IntervalQualifier;
import org.adb.engine.SessionLocal;
import org.adb.util.IntervalUtils;
import org.adb.value.TypeInfo;
import org.adb.value.Value;
import org.adb.value.ValueDecfloat;
import org.adb.value.ValueDouble;
import org.adb.value.ValueInterval;
import org.adb.value.ValueNull;
import org.adb.value.ValueNumeric;

/**
 * Data stored while calculating an AVG aggregate.
 */
final class AggregateDataAvg extends AggregateData {

    private final TypeInfo dataType;
    private long count;
    private double doubleValue;
    private BigDecimal decimalValue;
    private BigInteger integerValue;

    /**
     * @param dataType
     *            the data type of the computed result
     */
    AggregateDataAvg(TypeInfo dataType) {
        this.dataType = dataType;
    }

    @Override
    void add(SessionLocal session, Value v) {
        if (v == ValueNull.INSTANCE) {
            return;
        }
        count++;
        switch (dataType.getValueType()) {
        case Value.DOUBLE:
            doubleValue += v.getDouble();
            break;
        case Value.NUMERIC:
        case Value.DECFLOAT: {
            BigDecimal bd = v.getBigDecimal();
            decimalValue = decimalValue == null ? bd : decimalValue.add(bd);
            break;
        }
        default: {
            BigInteger bi = IntervalUtils.intervalToAbsolute((ValueInterval) v);
            integerValue = integerValue == null ? bi : integerValue.add(bi);
        }
        }
    }

    @Override
    Value getValue(SessionLocal session) {
        if (count == 0) {
            return ValueNull.INSTANCE;
        }
        Value v;
        int valueType = dataType.getValueType();
        switch (valueType) {
        case Value.DOUBLE:
            v = ValueDouble.get(doubleValue / count);
            break;
        case Value.NUMERIC:
            v = ValueNumeric
                    .get(decimalValue.divide(BigDecimal.valueOf(count), dataType.getScale(), RoundingMode.HALF_DOWN));
            break;
        case Value.DECFLOAT:
            v = ValueDecfloat.divide(decimalValue, BigDecimal.valueOf(count), dataType);
            break;
        default:
            v = IntervalUtils.intervalFromAbsolute(IntervalQualifier.valueOf(valueType - Value.INTERVAL_YEAR),
                    integerValue.divide(BigInteger.valueOf(count)));
        }
        return v.castTo(dataType, session);
    }

}
