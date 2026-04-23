/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.adb.value;

import java.math.BigDecimal;

import org.adb.api.ErrorCode;
import org.adb.engine.Constants;
import org.adb.message.DbException;

/**
 * Base class for BigDecimal-based values.
 */
abstract class ValueBigDecimalBase extends Value {

    final BigDecimal value;

    TypeInfo type;

    ValueBigDecimalBase(BigDecimal value) {
        if (value != null) {
            if (value.getClass() != BigDecimal.class) {
                throw DbException.get(ErrorCode.INVALID_CLASS_2, BigDecimal.class.getName(),
                        value.getClass().getName());
            }
            int length = value.precision();
            if (length > Constants.MAX_NUMERIC_PRECISION) {
                throw DbException.getValueTooLongException(getTypeName(getValueType()), value.toString(), length);
            }
        }
        this.value = value;
    }

}
